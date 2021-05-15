package com.intuit.maven.extensions.build.scanner;

import static java.lang.System.currentTimeMillis;

import com.intuit.maven.extensions.build.scanner.infra.DataStorage;
import com.intuit.maven.extensions.build.scanner.infra.MongoDataStorage;
import com.intuit.maven.extensions.build.scanner.model.Mojo;
import com.intuit.maven.extensions.build.scanner.model.MojoProfile;
import com.intuit.maven.extensions.build.scanner.model.ProjectProfile;
import com.intuit.maven.extensions.build.scanner.model.SessionProfile;
import com.intuit.maven.extensions.build.scanner.model.Status;
import com.intuit.maven.extensions.build.scanner.util.Util;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Named
@Singleton
public class LifecycleProfiler extends AbstractEventSpy {
  private static final Logger LOGGER = LoggerFactory.getLogger(LifecycleProfiler.class.getName());
  private final AtomicInteger threadIndexGenerator = new AtomicInteger();
  private final ThreadLocal<Integer> threadIndex =
      ThreadLocal.withInitial(threadIndexGenerator::incrementAndGet);
  private final boolean enabled;
  private final Function<SessionProfile, DataStorage> dataStorageFactory;
  private DataStorage dataStorage;
  private SessionProfile sessionProfile;
  private long lastCheckPoint = currentTimeMillis();

  public LifecycleProfiler() {
    this("1".equals(System.getenv("MAVEN_BUILD_SCANNER")), MongoDataStorage::new);
  }

  LifecycleProfiler(boolean enabled, Function<SessionProfile, DataStorage> dataStorageFactory) {
    this.enabled = enabled;
    this.dataStorageFactory = dataStorageFactory;
  }

  @Override
  public void init(Context context) {}

  @Override
  public synchronized void onEvent(Object event) {

    if (!(enabled && event instanceof ExecutionEvent)) {
      return;
    }

    ExecutionEvent executionEvent = (ExecutionEvent) event;
    MavenProject mavenProject = executionEvent.getProject();

    switch (executionEvent.getType()) {
      case SessionStarted:
        {
          MavenSession session = executionEvent.getSession();
          // TODO its rather nice having the report based on the first project run, rather than
          //      the top-level project as that indicates quite a different build occurring
          sessionProfile =
              SessionProfile.builder()
                  .id(UUID.randomUUID().toString())
                  .project(Util.project(mavenProject))
                  .hostname(Objects.requireNonNull(Util.hostname()))
                  .username(System.getProperty("user.name"))
                  .command(command(session.getRequest()))
                  .goals(session.getGoals())
                  .branch(getBranch(mavenProject))
                  .status(Status.PENDING)
                  .build();
          sessionProfile.setStartTime(currentTimeMillis());

          LOGGER.info(
              "Creating Maven build scanner session profile "
                  + sessionProfile.getProject().getId()
                  + "#"
                  + sessionProfile.getId());

          session.getProjectDependencyGraph().getSortedProjects().stream()
              .map(Util::project)
              .map(childProject -> new ProjectProfile(childProject, Status.PENDING))
              .forEach(sessionProfile::addProjectProfile);

          dataStorage = dataStorageFactory.apply(sessionProfile);
          dataStorage.open();
          break;
        }
      case SessionEnded:
        {
          sessionProfile.setEndTime(currentTimeMillis());
          Status status =
              Optional.of(executionEvent)
                  .map(ExecutionEvent::getSession)
                  .map(MavenSession::getResult)
                  .filter(MavenExecutionResult::hasExceptions)
                  .map(b -> Status.FAILED)
                  .orElse(Status.SUCCEEDED);
          sessionProfile.setStatus(status);

          dataStorage.close();

          LOGGER.info(
              "Created Maven build scanner session profile "
                  + sessionProfile.getProject().getId()
                  + "#"
                  + sessionProfile.getId());
          LOGGER.info(
                "Open http://localhost:3000/?projectId={}&sessionId={} to view your Maven build"
                    + " scanner results",
              sessionProfile.getProject().getId(),
              sessionProfile.getId());
          break;
        }
      case ProjectStarted:
        {
          sessionProfile.getProjectProfile(Util.project(mavenProject)).setStartTime(currentTimeMillis());
          break;
        }
      case ProjectSucceeded:
      case ProjectFailed:
        {
          ProjectProfile projectProfile = sessionProfile.getProjectProfile(Util.project(mavenProject));
          projectProfile.setStatus(
              Status.valueOf(
                  executionEvent
                      .getType()
                      .name()
                      .toUpperCase()
                      .substring(7) // 7 = length of "Project"
                  ));
          projectProfile.setEndTime(currentTimeMillis());
          sessionProfile.setEndTime(currentTimeMillis());
          maybeCheckPoint();
          break;
        }
      case MojoStarted:
        {
          MojoExecution mojoExecution = executionEvent.getMojoExecution();
          MojoProfile mojoProfile =
              new MojoProfile(
                  mojo(mojoExecution),
                  mojoExecution.getExecutionId(),
                  mojoExecution.getGoal(),
                  Status.PENDING,
                  threadIndex.get());
          mojoProfile.setStartTime(currentTimeMillis());

          sessionProfile.getProjectProfile(Util.project(mavenProject)).addMojoProfile(mojoProfile);
          break;
        }
      case MojoSucceeded:
      case MojoFailed:
        {
          MojoExecution mojoExecution = executionEvent.getMojoExecution();
          MojoProfile mojoProfile =
              sessionProfile
                  .getProjectProfile(Util.project(mavenProject))
                  .getMojoProfile(
                      mojo(mojoExecution), mojoExecution.getExecutionId(), mojoExecution.getGoal());
          mojoProfile.setEndTime(currentTimeMillis());

          mojoProfile.setStatus(
              Status.valueOf(
                  executionEvent.getType().name().toUpperCase().substring(4) // 4 = length of "Mojo"
                  ));
        }
        break;
    }
  }

  private void maybeCheckPoint() {
    if (currentTimeMillis() - lastCheckPoint > 30_0000) {
      LOGGER.info("Requesting check-point");
      dataStorage.checkPoint();
      lastCheckPoint = currentTimeMillis();
    }
  }

  private String getBranch(MavenProject mavenProject) {

    try {
      File basedir = mavenProject.getBasedir();
      File gitHead;
      do {
        gitHead = new File(basedir, ".git/HEAD");
        basedir = basedir.getParentFile();
      } while (!gitHead.exists());

      return Files.readAllLines(gitHead.toPath()).stream()
          .map(line -> line.replaceFirst(".*/", ""))
          .findFirst()
          .orElseThrow(IOException::new);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private String command(MavenExecutionRequest request) {
    List<String> out = new ArrayList<>();

    out.add("mvn");

    out.add("-s " + request.getUserSettingsFile());

    out.add("-T " + request.getDegreeOfConcurrency());

    request.getActiveProfiles().stream().map(profile -> "-P" + profile).forEach(out::add);

    request.getUserProperties().entrySet().stream()
        .map(entry -> "-D" + entry.getKey() + "=" + entry.getValue())
        .forEach(out::add);

    out.addAll(request.getGoals());

    return String.join(" ", out);
  }

  private Mojo mojo(MojoExecution mojoExecution) {
    return new Mojo(
        mojoExecution.getGroupId(), mojoExecution.getArtifactId(), mojoExecution.getVersion());
  }
}
