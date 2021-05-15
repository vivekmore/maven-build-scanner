package com.intuit.maven.extensions.build.scanner.util;

import com.intuit.maven.extensions.build.scanner.model.Project;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.apache.maven.project.MavenProject;

public class Util {

  public static Project project(MavenProject mavenProject) {
    return new Project(
        mavenProject.getGroupId(), mavenProject.getArtifactId(), mavenProject.getVersion());
  }

  public static String hostname() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      return null;
    }
  }
}
