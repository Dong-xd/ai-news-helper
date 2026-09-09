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

/**
 * 新闻去重和区域选择工具。
 *
 * <p>该类只处理集合和字符串规范化，不负责 RSS、AI 或邮件等外部交互。</p>
 */
public final class NewsDuplicateUtil {

    /**
     * 禁止实例化工具类。
     */
    private NewsDuplicateUtil() {
    }

    /**
     * 按发布时间倒序去除重复链接和重复标题，并返回不超过上限的新闻。
     *
     * @param items 待去重新闻集合
     * @param maxItems 返回结果的最大数量
     * @return 去重后的新闻列表
     */
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

    /**
     * 按国内新闻目标比例优先选择新闻，不足时使用另一地区的可用新闻补足上限。
     *
     * @param items 已去重的新闻集合
     * @param maxItems 返回结果的最大数量
     * @param domesticRatio 国内新闻目标占比，超出 0 到 1 时会被截断
     * @return 按发布时间倒序排列的区域筛选结果
     */
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
                    .filter(selected::add)
                    .limit(maxItems - result.size())
                    .forEach(result::add);
        }
        return result.stream()
                .sorted(Comparator.comparing(NewsItem::getPublishedAt).reversed())
                .toList();
    }

    /**
     * 使用 Unicode NFKC 规范化标题，并移除空白和大小写差异。
     *
     * @param title 原始标题
     * @return 用于比较的规范化标题
     */
    public static String normalizeTitle(String title) {
        String normalized = Normalizer.normalize(title == null ? "" : title, Normalizer.Form.NFKC);
        return normalized.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /**
     * 规范化新闻链接两端的空白。
     *
     * @param link 原始链接
     * @return 用于比较的链接
     */
    public static String normalizeLink(String link) {
        return link == null ? "" : link.trim();
    }
}
