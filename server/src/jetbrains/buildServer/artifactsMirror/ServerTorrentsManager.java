/*
 * Copyright (c) 2000-2012 by JetBrains s.r.o. All Rights Reserved.
 * Use is subject to license terms.
 */
package jetbrains.buildServer.artifactsMirror;

import jetbrains.buildServer.RootUrlHolder;
import jetbrains.buildServer.artifactsMirror.seeder.FileLink;
import jetbrains.buildServer.artifactsMirror.seeder.TorrentFileFactory;
import jetbrains.buildServer.artifactsMirror.seeder.TorrentsDirectorySeeder;
import jetbrains.buildServer.artifactsMirror.torrent.TorrentTracker;
import jetbrains.buildServer.log.Loggers;
import jetbrains.buildServer.serverSide.*;
import jetbrains.buildServer.serverSide.artifacts.BuildArtifact;
import jetbrains.buildServer.serverSide.artifacts.BuildArtifacts;
import jetbrains.buildServer.serverSide.artifacts.BuildArtifactsViewMode;
import jetbrains.buildServer.util.EventDispatcher;
import jetbrains.buildServer.util.FileUtil;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;

/**
 * @author Maxim Podkolzine (maxim.podkolzine@jetbrains.com)
 * @since 8.0
 */
public class ServerTorrentsManager extends BuildServerAdapter {
  private final TorrentTrackerManager myTorrentTrackerManager;
  private final TorrentsDirectorySeeder myTorrentsDirectorySeeder;
  private final RootUrlHolder myRootUrlHolder;
  private String myRootUrl;

  public ServerTorrentsManager(@NotNull ServerPaths serverPaths,
                               @NotNull RootUrlHolder rootUrlHolder,
                               @NotNull TorrentTrackerManager torrentTrackerManager,
                               @NotNull EventDispatcher<BuildServerListener> eventDispatcher) {
    myTorrentTrackerManager = torrentTrackerManager;
    myRootUrlHolder = rootUrlHolder;
    File torrentsStorage = new File(serverPaths.getPluginDataDirectory(), "torrents");
    torrentsStorage.mkdirs();
    myTorrentsDirectorySeeder = new TorrentsDirectorySeeder(torrentsStorage, new TorrentFileFactory() {
      public File createTorrentFile(@NotNull File sourceFile, @NotNull File parentDir) throws IOException {
        return myTorrentTrackerManager.createTorrent(sourceFile, parentDir);
      }
    });

    eventDispatcher.addListener(this);
  }

  @Override
  public void buildFinished(SRunningBuild build) {
    announceBuildArtifacts(build);
    restartSeeder();
  }

  @Override
  public void serverStartup() {
    super.serverStartup();
    myRootUrl = myRootUrlHolder.getRootUrl();
    restartSeeder();
  }

  private void restartSeeder() {
    boolean restartIsRequired = myTorrentsDirectorySeeder.isStopped() || !myRootUrl.equals(myRootUrlHolder.getRootUrl());
    if (!restartIsRequired) return;

    stopSeederIfStarted();

    try {
      myRootUrl = myRootUrlHolder.getRootUrl();
      myTorrentsDirectorySeeder.start(TorrentTracker.getServerAddress(myRootUrl));
    } catch (Exception e) {
      Loggers.SERVER.warn("Failed to start torrent seeder, error: " + e.toString());
    }
  }

  private void stopSeederIfStarted() {
    if (!myTorrentsDirectorySeeder.isStopped()) {
      myTorrentsDirectorySeeder.stop();
    }
  }

  @Override
  public void serverShutdown() {
    super.serverShutdown();
    stopSeederIfStarted();
  }

  @NotNull
  public File getTorrentFilesBaseDir(@NotNull SBuild build) {
    return getLinkDir(build);
  }

  @NotNull
  public Collection<File> getTorrentFiles(@NotNull SBuild build) {
    File baseDir = getTorrentFilesBaseDir(build);
    try {
      return FileUtil.findFiles(new FileFilter() {
        public boolean accept(File file) {
          return file.getName().endsWith(TorrentsDirectorySeeder.TORRENT_FILE_SUFFIX);
        }
      }, baseDir);
    } catch (Exception e) {
      return Collections.emptyList();
    }
  }

  @NotNull
  public File getTorrentFile(@NotNull SBuild build, @NotNull String torrentPath) {
    return new File(getTorrentFilesBaseDir(build), torrentPath);
  }

  private void announceBuildArtifacts(@NotNull final SBuild build) {
    final File artifactsDirectory = build.getArtifactsDirectory();

    BuildArtifacts artifacts = build.getArtifacts(BuildArtifactsViewMode.VIEW_DEFAULT);
    artifacts.iterateArtifacts(new BuildArtifacts.BuildArtifactsProcessor() {
      @NotNull
      public Continuation processBuildArtifact(@NotNull BuildArtifact artifact) {
        if (shouldCreateTorrentFor(artifact)) {
          File artifactFile = new File(artifactsDirectory, artifact.getRelativePath());
          File baseDir = getTorrentFilesBaseDir(build);
          File filePath = new File(baseDir, artifact.getRelativePath());
          File linkDir = filePath.getParentFile();

          try {
            FileLink.createLink(artifactFile, linkDir);
          } catch (IOException e) {
            //
          }
        }
        return Continuation.CONTINUE;
      }
    });
  }

  private File getLinkDir(@NotNull SBuild build) {
    return new File(myTorrentsDirectorySeeder.getStorageDirectory(),
                    build.getBuildTypeId() + File.separator + build.getBuildId());
  }

  private boolean shouldCreateTorrentFor(@NotNull BuildArtifact artifact) {
    long size = artifact.getSize();
    return !artifact.isDirectory() && size >= myTorrentTrackerManager.getFileSizeThresholdMb() * 1024 * 1024;
  }
}