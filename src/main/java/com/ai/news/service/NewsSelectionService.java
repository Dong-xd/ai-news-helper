package com.ai.news.service;

import java.time.Instant;
import java.util.List;

import com.ai.news.client.rss.RssFetcherClient;
import com.ai.news.model.dto.NewsItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 新闻选择服务。
 *
 * <p>该服务为业务编排层提供统一的新闻获取入口，具体 RSS 交互由客户端封装。</p>
 */
@Service
public class NewsSelectionService {

    /**
     * RSS 新闻抓取客户端。
     */
    @Autowired
    private RssFetcherClient rssFetcherClient;

    /**
     * 获取指定时间点对应时间窗口内的新闻。
     *
     * @param now 当前时间
     * @return 去重和筛选后的新闻列表
     */
    public List<NewsItem> fetchRecent(Instant now) {
        return rssFetcherClient.fetchRecent(now);
    }
}
