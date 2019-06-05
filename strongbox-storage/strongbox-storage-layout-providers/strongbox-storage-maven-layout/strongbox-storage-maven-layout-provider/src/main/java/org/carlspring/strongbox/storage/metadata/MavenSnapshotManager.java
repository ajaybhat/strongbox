package org.carlspring.strongbox.storage.metadata;

import org.carlspring.strongbox.artifact.MavenArtifactUtils;
import org.carlspring.strongbox.providers.io.RepositoryFiles;
import org.carlspring.strongbox.providers.io.RepositoryPath;
import org.carlspring.strongbox.storage.repository.Repository;

import javax.inject.Inject;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;

import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.Versioning;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.javatuples.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import static org.apache.maven.artifact.Artifact.VERSION_FILE_PATTERN;

/**
 * @author Kate Novik.
 */
@Component
public class MavenSnapshotManager
{

    private static final String TIMESTAMP_FORMAT = "yyyyMMdd.HHmmss";

    private static final Logger logger = LoggerFactory.getLogger(MavenSnapshotManager.class);

    @Inject
    private MavenMetadataManager mavenMetadataManager;

    public MavenSnapshotManager()
    {
    }

    public void deleteTimestampedSnapshotArtifacts(RepositoryPath basePath,
                                                   Versioning versioning,
                                                   int numberToKeep,
                                                   int keepPeriod)
            throws IOException,
                   XmlPullParserException
    {
        Repository repository = basePath.getRepository();
        if (!RepositoryFiles.artifactExists(basePath))
        {
            logger.error("Removal of timestamped Maven snapshot artifact: " + basePath + ".");
            
            return;
        }
        
        logger.debug("Removal of timestamped Maven snapshot artifact " + basePath +
                     " in '" + repository.getStorage()
                                         .getId() + ":" + repository.getId() + "'.");

        Pair<String, String> artifactGroup = MavenArtifactUtils.getDirectoryGA(basePath);
        String artifactGroupId = artifactGroup.getValue0();
        String artifactId = artifactGroup.getValue1();

        if (versioning.getVersions().isEmpty())
        {
            return;
        }
        
        for (String version : versioning.getVersions())
        {

            RepositoryPath versionDirectoryPath = basePath.resolve(ArtifactUtils.toSnapshotVersion(version));
            if (!removeTimestampedSnapshot(versionDirectoryPath, numberToKeep, keepPeriod))
            {
                continue;
            }

            logger.debug("Generate snapshot versioning metadata for " + versionDirectoryPath + ".");

            mavenMetadataManager.generateSnapshotVersioningMetadata(artifactGroupId,
                                                                    artifactId,
                                                                    versionDirectoryPath,
                                                                    version,
                                                                    true);
        }
    }

    private boolean removeTimestampedSnapshot(RepositoryPath basePath,
                                              int numberToKeep,
                                              int keepPeriod)
            throws IOException,
                   XmlPullParserException
    {
        Metadata metadata = mavenMetadataManager.readMetadata(basePath);

        if (metadata == null || metadata.getVersioning() == null)
        {
            return false;
        }
        
        /**
         * map of snapshots for removing
         * k - number of the build, v - version of the snapshot
         */
        Map<Integer, String> mapToRemove = getRemovableTimestampedSnapshots(metadata, numberToKeep, keepPeriod);

        if (mapToRemove.isEmpty())
        {
            return false;
        }
        List<String> removingSnapshots = new ArrayList<>();

        new ArrayList<>(mapToRemove.values()).forEach(e -> removingSnapshots.add(metadata.getArtifactId()
                                                                                         .concat("-")
                                                                                         .concat(e)
                                                                                         .concat(".jar")));

        try (final DirectoryStream<Path> directoryStream = Files.newDirectoryStream(basePath))
        {
            for (Path path : directoryStream)
            {
                if (!Files.isRegularFile(path))
                {
                    continue;
                }
                
                RepositoryPath repositoryPath = (RepositoryPath) path;
                final String filename = path.getFileName().toString();
                
                if (!removingSnapshots.contains(filename) ||
                    !RepositoryFiles.isArtifact(repositoryPath) ||
                    RepositoryFiles.isMetadata(repositoryPath))
                {
                    continue;
                }
                
                try
                {
                    RepositoryFiles.delete(repositoryPath, true);

                    RepositoryPath pomRepositoryPath = repositoryPath.resolveSibling(filename.replace(".jar", ".pom"));
                    
                    RepositoryFiles.delete(pomRepositoryPath,true);
                }
                catch (IOException ex)
                {
                    logger.error(ex.getMessage(), ex);
                }
            }
        }
        return true;
    }

    /**
     * To get map of removable timestamped snapshots
     *
     * @param metadata     type Metadata
     * @param numberToKeep type int
     * @param keepPeriod   type int
     * @return type Map<Integer, String>
     */
    private Map<Integer, String> getRemovableTimestampedSnapshots(Metadata metadata,
                                                                  int numberToKeep,
                                                                  int keepPeriod)
    {
        /**
         * map of the snapshots in metadata file
         * k - number of the build, v - version of the snapshot
         */
        Map<Integer, SnapshotVersion> snapshots = new HashMap<>();

        /**
         * map of snapshots for removing
         * k - number of the build, v - version of the snapshot
         */
        Map<Integer, String> mapToRemove = new HashMap<>();

        metadata.getVersioning()
                .getSnapshotVersions()
                .forEach(e ->
                         {

                             if ("jar".equals(e.getExtension()))
                             {
                                 String version = e.getVersion();
                                 Matcher matcher = VERSION_FILE_PATTERN.matcher(version);
                                 if (matcher.matches())
                                 {
                                     final int buildNumber = Integer.parseInt(matcher.group(5));
                                     final String timestamp = matcher.group(3);
                                     final SnapshotVersion snapshotVersion = new SnapshotVersion(version, buildNumber, timestamp);
                                     snapshots.put(snapshotVersion.buildNumber, snapshotVersion);
                                 }
                             }
                         });

        if (numberToKeep != 0 && snapshots.size() > numberToKeep)
        {
            snapshots.forEach((k, v) ->
                              {
                                  if (mapToRemove.size() < snapshots.size() - numberToKeep)
                                  {
                                      mapToRemove.put(k, v.version);
                                  }
                               });
        }
        else if (numberToKeep == 0 && keepPeriod != 0)
        {
            snapshots.forEach((k, v) ->
                               {
                                   try
                                   {
                                       if (keepPeriod < getDifferenceDays(v.timestamp))
                                       {
                                           mapToRemove.put(k, v.version);
                                       }
                                   }
                                   catch (ParseException e)
                                   {
                                       logger.error(e.getMessage(), e);
                                   }
                               });
        }

        return mapToRemove;
    }

    /**
     * To get day's number of keeping timestamp snapshot
     * @param buildTimestamp type String
     * @return days type int
     * @throws ParseException
     */
    private int getDifferenceDays(String buildTimestamp)
            throws ParseException
    {
        DateFormat formatter = new SimpleDateFormat(TIMESTAMP_FORMAT);
        Calendar calendar = Calendar.getInstance();

        String currentDate = formatter.format(calendar.getTime());

        Date d2 = formatter.parse(currentDate);
        Date d1 = formatter.parse(buildTimestamp);

        long diff = d2.getTime() - d1.getTime();

        return (int) diff / (24 * 60 * 60 * 1000);
    }

    private static class SnapshotVersion
    {

        private final String version;
        private final int buildNumber;
        private final String timestamp;

        private SnapshotVersion(String version,
                                int buildNumber,
                                String timestamp)
        {
            this.version = version;
            this.buildNumber = buildNumber;
            this.timestamp = timestamp;
        }
    }

}
