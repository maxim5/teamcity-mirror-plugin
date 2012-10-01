package jetbrains.buildServer.artifactsMirror.torrent;

import com.intellij.openapi.diagnostic.Logger;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.tracker.TrackedTorrent;
import com.turn.ttorrent.tracker.Tracker;
import jetbrains.buildServer.NetworkUtil;
import jetbrains.buildServer.serverSide.SBuildServer;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;

public class TorrentTracker {
  private final static Logger LOG = Logger.getInstance(TorrentTracker.class.getName());

  private SBuildServer myServer;
  private Tracker myTracker;

  public TorrentTracker(@NotNull SBuildServer server) {
    myServer = server;
  }

  public void start() {
    int freePort = NetworkUtil.getFreePort(6969);

    try {
      String rootUrl = myServer.getRootUrl();
      if (rootUrl.endsWith("/")) rootUrl = rootUrl.substring(0, rootUrl.length()-1);
      URI serverUrl = new URI(rootUrl);
      InetAddress serverAddress = InetAddress.getByName(serverUrl.getHost());
      myTracker = new Tracker(new InetSocketAddress(serverAddress, freePort));
      myTracker.start();
      LOG.info("Torrent tracker started on url: " + rootUrl + ":" + freePort);
    } catch (Exception e) {
      LOG.error("Failed to start torrent tracker, server URL is invalid: " + e.toString());
    }
  }

  public void stop() {
    if (myTracker != null) {
      LOG.info("Stopping torrent tracker");
      myTracker.stop();
    }
  }

  /**
   * Creates torrent for the given file and announces it in the tracker.
   * @param srcFile file to create torrent for
   * @return true if operation is successful or false otherwise
   */
  public boolean announceTorrent(@NotNull File srcFile) {
    if (myTracker == null) return false;

    File parentDir = srcFile.getParentFile();
    File torrentFile = new File(parentDir, srcFile.getName() + ".torrent");

    try {
      Torrent t;
      if (torrentFile.isFile()) {
        t = Torrent.load(torrentFile, null);
      } else {
        t = Torrent.create(srcFile, new URI(myServer.getRootUrl()), "TeamCity");
        t.save(torrentFile);
      }

      myTracker.announce(new TrackedTorrent(t));
      LOG.info("Torrent announced in tracker: " + srcFile.getAbsolutePath());
    } catch (Exception e) {
      LOG.warn("Failed to announce file in torrent tracker: " + e.toString());
      return false;
    }

    return true;
  }
}
