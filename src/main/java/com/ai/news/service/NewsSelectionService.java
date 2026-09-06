package com.ai.news.service;

import java.time.Instant;
import java.util.List;

import com.ai.news.client.rss.RssFetcherClient;
import com.ai.news.model.dto.NewsItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class NewsSelectionService {

    @Autowired
    private RssFetcherClient rssFetcherClient;

    public List<NewsItem> fetchRecent(Instant now) {
        return rssFetcherClient.fetchRecent(now);
    }
}
