package org.carlspring.strongbox.artifact;

import org.carlspring.strongbox.providers.io.RepositoryFiles;
import org.carlspring.strongbox.providers.io.RepositoryPath;

import java.io.IOException;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.index.artifact.Gav;
import org.apache.maven.index.artifact.M2GavCalculator;
import org.javatuples.Pair;

/**
 * @author Przemyslaw Fusik
 */
public class MavenArtifactUtils
{

    private static final M2GavCalculator M2_GAV_CALCULATOR = new M2GavCalculator();

    public static String convertArtifactToPath(Artifact artifact)
    {
        final Gav gav = new Gav(artifact.getGroupId(),
                                StringUtils.defaultString(artifact.getArtifactId()),
                                StringUtils.defaultString(artifact.getVersion()),
                                artifact.getClassifier(), artifact.getType(), null, null, null, false, null, false,
                                null);
        return M2_GAV_CALCULATOR.gavToPath(gav).substring(1);
    }

    public static Pair<String, String> getDirectoryGA(RepositoryPath directoryPath)
            throws IOException
    {
        String path = RepositoryFiles.relativizePath(directoryPath);
        if (path.endsWith("/"))
        {
            path = StringUtils.substringBeforeLast(path, "/");
        }
        return Pair.with(StringUtils.substringBeforeLast(path, "/").replaceAll("/", "."),
                         StringUtils.substringAfterLast(path, "/"));
    }

    public static MavenArtifact convertPathToArtifact(RepositoryPath repositoryPath)
            throws IOException
    {
        String path = RepositoryFiles.relativizePath(repositoryPath);
        Gav gav = convertPathToGav(path);
        return gav != null ? new MavenRepositoryArtifact(gav, repositoryPath) : null;
    }

    public static Gav convertPathToGav(RepositoryPath repositoryPath)
            throws IOException
    {
        String path = RepositoryFiles.relativizePath(repositoryPath);
        return convertPathToGav(path);
    }

    public static MavenArtifact convertPathToArtifact(String path)
    {
        Gav gav = convertPathToGav(path);
        return gav != null ? new MavenRepositoryArtifact(gav) : null;
    }

    public static Gav convertPathToGav(String path)
    {
        return M2_GAV_CALCULATOR.pathToGav(path);
    }

    public static String convertGavToPath(Gav gav)
    {
        return M2_GAV_CALCULATOR.gavToPath(gav);
    }

}
