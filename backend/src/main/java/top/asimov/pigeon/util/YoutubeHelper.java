package top.asimov.pigeon.util;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.Channel;
import com.google.api.services.youtube.model.ChannelListResponse;
import com.google.api.services.youtube.model.SearchListResponse;
import com.google.api.services.youtube.model.SearchResult;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import top.asimov.pigeon.exception.BusinessException;
import top.asimov.pigeon.service.AccountService;

@Log4j2
@Component
public class YoutubeHelper {

  private static final String APPLICATION_NAME = "My YouTube App";
  private static final JacksonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();

  private final AccountService accountService;
  private final MessageSource messageSource;
  private final YouTube youtubeService;

  public YoutubeHelper(AccountService accountService, MessageSource messageSource) {
    this.accountService = accountService;
    this.messageSource = messageSource;

    try {
      this.youtubeService = new YouTube.Builder(
          GoogleNetHttpTransport.newTrustedTransport(),
          JSON_FACTORY,
          null // No need for HttpRequestInitializer for API key access
      ).setApplicationName(APPLICATION_NAME).build();
    } catch (GeneralSecurityException | IOException e) {
      log.error("Failed to initialize YouTube service", e);
      throw new RuntimeException("Failed to initialize YouTube service", e);
    }
  }

  /**
   * 根据输入获取 YouTube 频道信息 支持多种输入格式:
   * 1. 直接的频道 ID: UCSJ4gkVC6NrvII8umztf0Ow
   * 2. @handle 链接: https://www.youtube.com/@LofiGirl
   * 3. /channel/ 链接: https://www.youtube.com/channel/UCSJ4gkVC6NrvII8umztf0Ow
   *
   * @param input 频道输入（URL 或 ID）
   * @return YouTube 频道信息
   */
  public Channel fetchYoutubeChannel(String input) {
    // 首先尝试直接提取频道 ID
    String channelId = extractChannelId(input);

    if (channelId != null) {
      // 直接使用频道 ID 获取信息
      return fetchYoutubeChannelByYoutubeChannelId(channelId);
    } else {
      // 使用传统的 handle 搜索方式
      String resolvedChannelId = fetchYoutubeChannelIdByUrl(input);
      return fetchYoutubeChannelByYoutubeChannelId(resolvedChannelId);
    }
  }

  /**
   * 从频道 URL 中提取 handle 例如: https://www.youtube.com/@LofiGirl -> LofiGirl
   *
   * @param channelUrl 频道 URL
   * @return 提取的 handle，如果无法提取则返回 null
   */
  private String getHandleFromUrl(String channelUrl) {
    if (channelUrl == null || !channelUrl.contains("@")) {
      return null;
    }
    int atIndex = channelUrl.lastIndexOf('@');
    int slashIndex = channelUrl.indexOf('/', atIndex);
    if (slashIndex > 0) {
      return channelUrl.substring(atIndex + 1, slashIndex);
    }
    return channelUrl.substring(atIndex + 1);
  }

  /**
   * 从输入中提取频道 ID 支持多种输入格式:
   * 1. 直接的频道 ID: UCSJ4gkVC6NrvII8umztf0Ow
   * 2. @handle 链接: https://www.youtube.com/@LofiGirl
   * 3. /channel/ 链接: https://www.youtube.com/channel/UCSJ4gkVC6NrvII8umztf0Ow
   *
   * @param input 输入字符串
   * @return 频道 ID，如果无法解析则返回 null
   */
  private String extractChannelId(String input) {
    if (input == null || input.trim().isEmpty()) {
      return null;
    }

    String trimmed = input.trim();

    // 1. 检查是否直接是频道 ID
    if (isYouTubeChannelId(trimmed)) {
      return trimmed;
    }

    // 2. 检查是否是 /channel/ 格式的链接
    if (trimmed.contains("/channel/")) {
      int channelIndex = trimmed.indexOf("/channel/");
      String channelId = trimmed.substring(channelIndex + 9); // "/channel/".length() = 9
      // 移除可能的查询参数
      int questionIndex = channelId.indexOf('?');
      if (questionIndex > 0) {
        channelId = channelId.substring(0, questionIndex);
      }
      // 移除可能的路径
      int slashIndex = channelId.indexOf('/');
      if (slashIndex > 0) {
        channelId = channelId.substring(0, slashIndex);
      }

      if (isYouTubeChannelId(channelId)) {
        return channelId;
      }
    }

    // 3. 如果不是以上格式，返回 null，让调用者使用传统的 handle 搜索方式
    return null;
  }

  /**
   * 使用频道 ID 获取频道详细信息
   *
   * @param channelId 频道 ID
   * @return 频道信息
   */
  private Channel fetchYoutubeChannelByYoutubeChannelId(String channelId) {
    try {
      String youtubeApiKey = accountService.getYoutubeApiKey();

      // 使用Channel ID获取频道的详细信息
      YouTube.Channels.List channelRequest = youtubeService.channels()
          .list("snippet,statistics,brandingSettings");
      channelRequest.setId(channelId);
      channelRequest.setKey(youtubeApiKey);

      ChannelListResponse response = channelRequest.execute();
      List<com.google.api.services.youtube.model.Channel> channels = response.getItems();

      if (ObjectUtils.isEmpty(channels)) {
        throw new BusinessException(messageSource.getMessage("youtube.channel.not.found", null,
            LocaleContextHolder.getLocale()));
      }

      return channels.get(0);
    } catch (IOException e) {
      throw new BusinessException(
          messageSource.getMessage("youtube.fetch.channel.failed", new Object[]{e.getMessage()},
              LocaleContextHolder.getLocale()));
    }
  }

  /**
   * 使用频道 URL 或 handle 搜索并获取频道 ID
   *
   * @param channelUrl 频道 URL 或 handle
   * @return 频道 ID
   */
  private String fetchYoutubeChannelIdByUrl(String channelUrl) {
    try {
      String youtubeApiKey = accountService.getYoutubeApiKey();

      // 从URL提取handle
      String handle = getHandleFromUrl(channelUrl);
      if (handle == null) {
        throw new BusinessException(
            messageSource.getMessage("youtube.invalid.url", null, LocaleContextHolder.getLocale()));
      }

      // 使用handle搜索以获取Channel ID
      YouTube.Search.List searchListRequest = youtubeService.search()
          .list("snippet")
          .setQ(handle) // 使用 handle 作为查询词
          .setType("channel") // 只搜索频道
          .setMaxResults(1L); // 我们只需要最相关的那个

      searchListRequest.setKey(youtubeApiKey);
      SearchListResponse response = searchListRequest.execute();
      List<SearchResult> searchResults = response.getItems();

      if (!CollectionUtils.isEmpty(searchResults)) {
        // 第一个结果就是我们想要的频道
        return searchResults.get(0).getSnippet().getChannelId();
      }
      throw new BusinessException(messageSource.getMessage("youtube.channel.not.found", null,
          LocaleContextHolder.getLocale()));
    } catch (IOException e) {
      throw new BusinessException(
          messageSource.getMessage("youtube.fetch.channel.failed", new Object[]{e.getMessage()},
              LocaleContextHolder.getLocale()));
    }
  }

  /**
   * 检测输入是否为 YouTube 频道 ID YouTube 频道 ID 格式: UC + 22个字符，总共24个字符
   *
   * @param input 输入字符串
   * @return 如果是频道 ID 返回 true，否则返回 false
   */
  private boolean isYouTubeChannelId(String input) {
    if (input == null || input.trim().isEmpty()) {
      return false;
    }

    String trimmed = input.trim();
    // YouTube 频道 ID 通常以 UC 开头，总长度为 24 个字符
    // 例如: UCSJ4gkVC6NrvII8umztf0Ow
    return trimmed.length() == 24 &&
        trimmed.startsWith("UC") &&
        trimmed.matches("^[A-Za-z0-9_-]{24}$");
  }

}
