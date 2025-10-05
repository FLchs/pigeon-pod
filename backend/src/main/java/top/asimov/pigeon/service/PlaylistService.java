package top.asimov.pigeon.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PostConstruct;
import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import top.asimov.pigeon.constant.FeedSource;
import top.asimov.pigeon.constant.PlaylistEpisodeSort;
import top.asimov.pigeon.constant.Youtube;
import top.asimov.pigeon.event.DownloadTaskEvent.DownloadTargetType;
import top.asimov.pigeon.exception.BusinessException;
import top.asimov.pigeon.mapper.PlaylistEpisodeMapper;
import top.asimov.pigeon.mapper.PlaylistMapper;
import top.asimov.pigeon.model.Episode;
import top.asimov.pigeon.model.FeedConfigUpdateResult;
import top.asimov.pigeon.model.FeedPack;
import top.asimov.pigeon.model.FeedSaveResult;
import top.asimov.pigeon.model.Playlist;
import top.asimov.pigeon.model.PlaylistEpisode;
import top.asimov.pigeon.util.FeedEpisodeUtils;
import top.asimov.pigeon.util.YoutubeHelper;
import top.asimov.pigeon.util.YoutubeVideoHelper;

@Log4j2
@Service
public class PlaylistService extends AbstractFeedService<Playlist> {

  @Value("${pigeon.base-url}")
  private String appBaseUrl;

  private final PlaylistMapper playlistMapper;
  private final PlaylistEpisodeMapper playlistEpisodeMapper;
  private final YoutubeHelper youtubeHelper;
  private final YoutubeVideoHelper youtubeVideoHelper;
  private final AccountService accountService;
  private final MessageSource messageSource;

  public PlaylistService(PlaylistMapper playlistMapper, PlaylistEpisodeMapper playlistEpisodeMapper,
      EpisodeService episodeService, ApplicationEventPublisher eventPublisher,
      YoutubeHelper youtubeHelper, YoutubeVideoHelper youtubeVideoHelper,
      AccountService accountService, MessageSource messageSource) {
    super(episodeService, eventPublisher, messageSource);
    this.playlistMapper = playlistMapper;
    this.playlistEpisodeMapper = playlistEpisodeMapper;
    this.youtubeHelper = youtubeHelper;
    this.youtubeVideoHelper = youtubeVideoHelper;
    this.accountService = accountService;
    this.messageSource = messageSource;
  }

  @PostConstruct
  private void init() {
    if (appBaseUrl != null && appBaseUrl.endsWith("/")) {
      appBaseUrl = appBaseUrl.substring(0, appBaseUrl.length() - 1);
      log.info("已移除 appBaseUrl 末尾的斜杠，处理后的值为: {}", appBaseUrl);
    }
  }

  public List<Playlist> selectPlaylistList() {
    return playlistMapper.selectPlaylistsByLastPublishedAt();
  }

  public Playlist playlistDetail(String id) {
    Playlist playlist = playlistMapper.selectById(id);
    if (playlist == null) {
      throw new BusinessException(
          messageSource.getMessage("playlist.not.found", new Object[]{id},
              LocaleContextHolder.getLocale()));
    }
    playlist.setOriginalUrl(Youtube.PLAYLIST_URL + playlist.getId());
    return playlist;
  }

  public String getPlaylistRssFeedUrl(String playlistId) {
    Playlist playlist = playlistMapper.selectById(playlistId);
    if (ObjectUtils.isEmpty(playlist)) {
      throw new BusinessException(
          messageSource.getMessage("playlist.not.found", new Object[]{playlistId},
              LocaleContextHolder.getLocale()));
    }
    String apiKey = accountService.getApiKey();
    if (ObjectUtils.isEmpty(apiKey)) {
      throw new BusinessException(
          messageSource.getMessage("playlist.api.key.failed", null,
              LocaleContextHolder.getLocale()));
    }
    return appBaseUrl + "/api/rss/playlist/" + playlistId + ".xml?apikey=" + apiKey;
  }

  @Transactional
  public FeedConfigUpdateResult updatePlaylistConfig(String playlistId, Playlist configuration) {
    FeedConfigUpdateResult result = updateFeedConfig(playlistId, configuration);
    log.info("播放列表 {} 配置更新成功", playlistId);
    return result;
  }

  @Override
  protected void applyAdditionalMutableFields(Playlist existingFeed, Playlist configuration) {
    existingFeed.setEpisodeSort(configuration.getEpisodeSort());
  }

  public FeedPack<Playlist> fetchPlaylist(String playlistUrl) {
    if (ObjectUtils.isEmpty(playlistUrl)) {
      throw new BusinessException(
          messageSource.getMessage("playlist.source.empty", null,
              LocaleContextHolder.getLocale()));
    }

    com.google.api.services.youtube.model.Playlist ytPlaylist;

    try {
      ytPlaylist = youtubeHelper.fetchYoutubePlaylist(playlistUrl);
    } catch (Exception e) {
      log.error("获取播放列表信息失败，输入: {}, 错误: {}", playlistUrl, e.getMessage());
      throw new BusinessException(messageSource.getMessage("youtube.fetch.playlist.failed",
          new Object[]{e.getMessage()}, LocaleContextHolder.getLocale()));
    }

    String ytPlaylistId = ytPlaylist.getId();
    // 先抓取预览视频，用于挑选无黑边的封面
    List<Episode> episodes = youtubeVideoHelper.fetchPlaylistVideos(ytPlaylistId,
        DEFAULT_FETCH_NUM);

    String playlistFallbackCover = ytPlaylist.getSnippet() != null
        && ytPlaylist.getSnippet().getThumbnails() != null
        && ytPlaylist.getSnippet().getThumbnails().getHigh() != null
        ? ytPlaylist.getSnippet().getThumbnails().getHigh().getUrl()
        : null;

    String episodeCover = episodes != null && !episodes.isEmpty()
        ? (episodes.get(0).getMaxCoverUrl() != null
        ? episodes.get(0).getMaxCoverUrl()
        : episodes.get(0).getDefaultCoverUrl())
        : null;

    Playlist fetchedPlaylist = Playlist.builder()
        .id(ytPlaylistId)
        .title(ytPlaylist.getSnippet().getTitle())
        .ownerId(ytPlaylist.getSnippet().getChannelId())
        // 使用首个视频的大图作为封面，避免 Playlist 默认缩略图的黑边；否则回退到 playlist 自带缩略图
        .coverUrl(episodeCover != null ? episodeCover : playlistFallbackCover)
        .description(ytPlaylist.getSnippet().getDescription())
        .subscribedAt(LocalDateTime.now())
        .source(FeedSource.YOUTUBE.name())
        .originalUrl(playlistUrl)
        .build();

    return FeedPack.<Playlist>builder().feed(fetchedPlaylist).episodes(episodes).build();
  }

  public FeedPack<Playlist> previewPlaylist(Playlist playlist) {
    return previewFeed(playlist);
  }

  @Transactional
  public FeedSaveResult<Playlist> savePlaylist(Playlist playlist) {
    FeedSaveResult<Playlist> result = saveFeed(playlist);
    if (result.isAsync()) {
      log.info("播放列表 {} 设置的初始视频数量较多({}), 启用异步处理模式", playlist.getTitle(),
          playlist.getInitialEpisodes());
    } else {
      log.info("播放列表 {} 设置的初始视频数量较少({}), 使用同步处理模式", playlist.getTitle(),
          playlist.getInitialEpisodes());
    }
    return result;
  }

  public List<Playlist> findDueForSync(LocalDateTime checkTime) {
    List<Playlist> playlists = playlistMapper.selectList(new LambdaQueryWrapper<>());
    return playlists.stream()
        .filter(p -> p.getLastSyncTimestamp() == null ||
            p.getLastSyncTimestamp().isBefore(checkTime))
        .collect(Collectors.toList());
  }

  @Transactional
  public void deletePlaylist(String playlistId) {
    log.info("开始删除播放列表: {}", playlistId);

    Playlist playlist = playlistMapper.selectById(playlistId);
    if (playlist == null) {
      throw new BusinessException(
          messageSource.getMessage("playlist.not.found", new Object[]{playlistId},
              LocaleContextHolder.getLocale()));
    }

    List<Episode> playlistEpisodes = episodeService().getEpisodesByPlaylistId(playlistId);
    LinkedHashMap<String, Episode> uniqueEpisodes = new LinkedHashMap<>();
    for (Episode episode : playlistEpisodes) {
      if (episode != null && StringUtils.hasText(episode.getId())) {
        uniqueEpisodes.putIfAbsent(episode.getId(), episode);
      }
    }

    playlistEpisodeMapper.deleteByPlaylistId(playlistId);

    int result = playlistMapper.deleteById(playlistId);
    if (result > 0) {
      log.info("播放列表 {} 删除成功", playlist.getTitle());
      removeOrphanEpisodes(uniqueEpisodes.values());
    } else {
      log.error("播放列表 {} 删除失败", playlist.getTitle());
      throw new BusinessException(
          messageSource.getMessage("playlist.delete.failed", null,
              LocaleContextHolder.getLocale()));
    }
  }

  @Transactional
  public void refreshPlaylist(Playlist playlist) {
    log.info("正在同步播放列表: {}", playlist.getTitle());
    refreshFeed(playlist);
  }

  @Transactional
  public void processPlaylistInitializationAsync(String playlistId, Integer initialEpisodes,
      String containKeywords, String excludeKeywords, Integer minimumDuration) {
    log.info("开始异步处理播放列表初始化，播放列表ID: {}, 初始视频数量: {}", playlistId,
        initialEpisodes);

    try {
      Playlist playlist = playlistMapper.selectById(playlistId);
      PlaylistEpisodeSort sort = PlaylistEpisodeSort.fromValue(
          playlist != null ? playlist.getEpisodeSort() : null);
      int episodesToFetch = initialEpisodes != null && initialEpisodes > 0
          ? initialEpisodes
          : DEFAULT_FETCH_NUM;
      List<Episode> episodes = fetchEpisodesBySort(playlistId, sort, episodesToFetch,
          null, containKeywords, excludeKeywords, minimumDuration);

      if (episodes.isEmpty()) {
        log.info("播放列表 {} 没有找到任何视频。", playlistId);
        return;
      }

      FeedEpisodeUtils.findLatestEpisode(episodes).ifPresent(latest -> {
        if (playlist != null) {
          playlist.setLastSyncVideoId(latest.getId());
          playlist.setLastSyncTimestamp(LocalDateTime.now());
          playlistMapper.updateById(playlist);
        }
      });

      if (playlist != null) {
        persistEpisodesAndPublish(playlist, episodes);
      } else {
        episodeService().saveEpisodes(prepareEpisodesForPersistence(episodes));
        FeedEpisodeUtils.publishEpisodesCreated(eventPublisher(), this, episodes);
        upsertPlaylistEpisodes(playlistId, episodes);
      }

      log.info("播放列表 {} 异步初始化完成，保存了 {} 个视频", playlistId, episodes.size());

    } catch (Exception e) {
      log.error("播放列表 {} 异步初始化失败: {}", playlistId, e.getMessage(), e);
    }
  }

  @Transactional
  public void processPlaylistDownloadHistoryAsync(String playlistId, Integer episodesToDownload,
      String containKeywords, String excludeKeywords, Integer minimumDuration) {
    PlaylistEpisode earliestEpisode = playlistEpisodeMapper.selectEarliestByPlaylistId(playlistId);
    if (earliestEpisode == null || earliestEpisode.getPublishedAt() == null) {
      log.warn("播放列表 {} 尚无历史节目数据，跳过历史下载。", playlistId);
      return;
    }
    LocalDateTime earliestTime = earliestEpisode.getPublishedAt().minusSeconds(1);
    log.info("播放列表 {} 开始重新下载历史节目，准备下载 {} 个视频", playlistId, episodesToDownload);
    try {
      List<Episode> episodes = youtubeVideoHelper.fetchPlaylistVideosBeforeDate(playlistId,
          episodesToDownload, earliestTime,
          containKeywords, excludeKeywords, minimumDuration);

      if (episodes.isEmpty()) {
        log.info("播放列表 {} 没有找到任何历史视频。", playlistId);
        return;
      }

      Playlist playlist = playlistMapper.selectById(playlistId);
      if (playlist != null) {
        persistEpisodesAndPublish(playlist, episodes);
      } else {
        episodeService().saveEpisodes(prepareEpisodesForPersistence(episodes));
        FeedEpisodeUtils.publishEpisodesCreated(eventPublisher(), this, episodes);
        upsertPlaylistEpisodes(playlistId, episodes);
      }

      log.info("播放列表 {} 历史节目处理完成，新增 {} 个视频", playlistId, episodes.size());
    } catch (Exception e) {
      log.error("播放列表 {} 重新下载历史节目失败: {}", playlistId, e.getMessage(), e);
    }
  }

  private void upsertPlaylistEpisodes(String playlistId, List<Episode> episodes) {
    for (Episode episode : episodes) {
      int count = playlistEpisodeMapper.countByPlaylistAndEpisode(playlistId, episode.getId());
      int affected;
      if (count > 0) {
        affected = playlistEpisodeMapper.updateMapping(playlistId, episode.getId(),
            episode.getPublishedAt());
      } else {
        affected = playlistEpisodeMapper.insertMapping(playlistId, episode.getId(),
            episode.getPublishedAt());
      }
      if (affected <= 0) {
        log.warn("更新播放列表 {} 与节目 {} 的关联失败", playlistId, episode.getId());
      }
    }
  }

  private List<Episode> fetchEpisodesBySort(Playlist playlist, int fetchNum,
      String containKeywords, String excludeKeywords, Integer minimalDuration) {
    PlaylistEpisodeSort sort = PlaylistEpisodeSort.fromValue(playlist.getEpisodeSort());
    return fetchEpisodesBySort(playlist.getId(), sort, fetchNum, null,
        containKeywords, excludeKeywords, minimalDuration);
  }

  private List<Episode> fetchEpisodesBySort(Playlist playlist, int fetchNum,
      String lastSyncedVideoId, String containKeywords, String excludeKeywords,
      Integer minimalDuration) {
    PlaylistEpisodeSort sort = PlaylistEpisodeSort.fromValue(playlist.getEpisodeSort());
    return fetchEpisodesBySort(playlist.getId(), sort, fetchNum, lastSyncedVideoId,
        containKeywords, excludeKeywords, minimalDuration);
  }

  private List<Episode> fetchEpisodesBySort(String playlistId, PlaylistEpisodeSort sort,
      int fetchNum, String lastSyncedVideoId, String containKeywords,
      String excludeKeywords, Integer minimalDuration) {
    if (sort.isDescendingPosition()) {
      if (lastSyncedVideoId != null) {
        return youtubeVideoHelper.fetchPlaylistVideosDescending(playlistId, fetchNum,
            lastSyncedVideoId, containKeywords, excludeKeywords, minimalDuration);
      }
      return youtubeVideoHelper.fetchPlaylistVideosDescending(playlistId, fetchNum,
          containKeywords, excludeKeywords, minimalDuration);
    }

    if (lastSyncedVideoId != null) {
      return youtubeVideoHelper.fetchPlaylistVideos(playlistId, fetchNum, lastSyncedVideoId,
          containKeywords, excludeKeywords, minimalDuration);
    }

    return youtubeVideoHelper.fetchPlaylistVideos(playlistId, fetchNum, containKeywords,
        excludeKeywords, minimalDuration);
  }

  private void removeOrphanEpisodes(Collection<Episode> episodes) {
    if (episodes == null || episodes.isEmpty()) {
      return;
    }

    Set<String> candidateDirectories = new HashSet<>();
    for (Episode episode : episodes) {
      long orhanEpisode = playlistEpisodeMapper.isOrhanEpisode(episode.getId());
      if (orhanEpisode == 0) {
        continue;
      }

      String audioFilePath = episode.getAudioFilePath();
      try {
        int deleteResult = episodeService().deleteEpisodeById(episode.getId());
        if (deleteResult > 0 && StringUtils.hasText(audioFilePath)) {
          File audioFile = new File(audioFilePath);
          File parentDir = audioFile.getParentFile();
          if (parentDir != null) {
            candidateDirectories.add(parentDir.getAbsolutePath());
          }
        }
      } catch (BusinessException ex) {
        log.error("删除播放列表孤立节目 {} 失败: {}", episode.getId(), ex.getMessage(), ex);
      }
    }

    cleanupEmptyDirectories(candidateDirectories);
  }

  private void cleanupEmptyDirectories(Set<String> directories) {
    if (directories == null || directories.isEmpty()) {
      return;
    }
    for (String directoryPath : directories) {
      if (!StringUtils.hasText(directoryPath)) {
        continue;
      }
      try {
        File directory = new File(directoryPath);
        if (directory.exists() && directory.isDirectory()) {
          File[] files = directory.listFiles();
          if (files != null && files.length == 0) {
            boolean deleted = directory.delete();
            if (deleted) {
              log.info("空的播放列表音频文件夹删除成功: {}", directoryPath);
            } else {
              log.warn("空的播放列表音频文件夹删除失败: {}", directoryPath);
            }
          }
        }
      } catch (Exception ex) {
        log.error("检查或删除播放列表音频文件夹时出错: {}", directoryPath, ex);
      }
    }
  }

  @Override
  protected Optional<Playlist> findFeedById(String feedId) {
    return Optional.ofNullable(playlistMapper.selectById(feedId));
  }

  @Override
  protected int updateFeed(Playlist feed) {
    return playlistMapper.updateById(feed);
  }

  @Override
  protected void insertFeed(Playlist feed) {
    playlistMapper.insert(feed);
  }

  @Override
  protected DownloadTargetType downloadTargetType() {
    return DownloadTargetType.PLAYLIST;
  }

  @Override
  protected List<Episode> fetchEpisodes(Playlist feed, int fetchNum) {
    return fetchEpisodesBySort(feed, fetchNum, feed.getContainKeywords(),
        feed.getExcludeKeywords(), feed.getMinimumDuration());
  }

  @Override
  protected List<Episode> fetchIncrementalEpisodes(Playlist feed) {
    return fetchEpisodesBySort(feed, MAX_FETCH_NUM, feed.getLastSyncVideoId(),
        feed.getContainKeywords(), feed.getExcludeKeywords(), feed.getMinimumDuration());
  }

  @Override
  protected List<Episode> prepareEpisodesForPersistence(List<Episode> episodes) {
    return new ArrayList<>(episodes);
  }

  @Override
  protected void afterEpisodesPersisted(Playlist feed, List<Episode> episodes) {
    if (feed != null) {
      upsertPlaylistEpisodes(feed.getId(), episodes);
      // 使用最新一期节目的大图更新播放列表封面，避免播放列表默认缩略图的黑边
      if (!ObjectUtils.isEmpty(episodes)) {
        Episode latest = episodes.get(0);
        String candidateCover = latest.getMaxCoverUrl() != null
            ? latest.getMaxCoverUrl()
            : latest.getDefaultCoverUrl();
        if (StringUtils.hasText(candidateCover) && !candidateCover.equals(feed.getCoverUrl())) {
          feed.setCoverUrl(candidateCover);
          updateFeed(feed);
        }
      }
    }
  }

  @Override
  protected org.apache.logging.log4j.Logger logger() {
    return log;
  }
}
