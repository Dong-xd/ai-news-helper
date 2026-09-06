package com.ai.news.util;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.ai.news.model.dto.NewsItem;

public final class NewsDuplicateUtil {

    private NewsDuplicateUtil() {
    }

    public static List<NewsItem> deduplicate(Collection<NewsItem> items, int maxItems) {
        if (maxItems <= 0) {
            return List.of();
        }
        List<NewsItem> sortedItems = items.stream()
                .sorted(Comparator.comparing(NewsItem::getPublishedAt).reversed())
                .toList();
        Set<String> links = new HashSet<>();
        Set<String> titles = new HashSet<>();
        List<NewsItem> result = new ArrayList<>();
        for (NewsItem item : sortedItems) {
            String link = normalizeLink(item.getLink());
            String title = normalizeTitle(item.getTitle());
            if (!links.add(link) || !titles.add(title)) {
                continue;
            }
            result.add(item);
            if (result.size() >= maxItems) {
                break;
            }
        }
        return result;
    }

    public static List<NewsItem> selectByRegion(Collection<NewsItem> items, int maxItems, double domesticRatio) {
        if (maxItems <= 0) {
            return List.of();
        }
        double normalizedRatio = Math.max(0, Math.min(1, domesticRatio));
        int domesticTarget = (int) Math.round(maxItems * normalizedRatio);
        int internationalTarget = maxItems - domesticTarget;
        List<NewsItem> sortedItems = items.stream()
                .sorted(Comparator.comparing(NewsItem::getPublishedAt).reversed())
                .toList();
        List<NewsItem> domesticItems = sortedItems.stream()
                .filter(item -> "国内".equals(item.getRegion()))
                .limit(domesticTarget)
                .toList();
        List<NewsItem> internationalItems = sortedItems.stream()
                .filter(item -> !"国内".equals(item.getRegion()))
                .limit(internationalTarget)
                .toList();
        List<NewsItem> result = new ArrayList<>(maxItems);
        result.addAll(domesticItems);
        result.addAll(internationalItems);
        if (result.size() < maxItems) {
            Set<NewsItem> selected = new HashSet<>(result);
            sortedItems.stream()
                    .filter(item -> selected.add(item))
                    .limit(maxItems - result.size())
                    .forEach(result::add);
        }
        return result.stream()
                .sorted(Comparator.comparing(NewsItem::getPublishedAt).reversed())
                .toList();
    }

    public static String normalizeTitle(String title) {
        String normalized = Normalizer.normalize(title == null ? "" : title, Normalizer.Form.NFKC);
        return normalized.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    public static String normalizeLink(String link) {
        return link == null ? "" : link.trim();
    }
}
