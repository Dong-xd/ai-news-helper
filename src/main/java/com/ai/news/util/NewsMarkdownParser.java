package com.ai.news.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析和重新生成 AI 新闻摘要使用的固定 Markdown 结构。
 */
public final class NewsMarkdownParser {

    public static final List<String> BOARD_NAMES = List.of("重磅发布", "技术前沿", "行业动态", "开源工具");
    public static final List<String> FIELD_NAMES = List.of("来源", "发布时间", "分类", "重要性", "摘要", "关注理由");

    private static final Pattern OVERVIEW_PATTERN = Pattern.compile("^#\\s+今日概览\\s*[：:]?\\s*$");
    private static final Pattern BOARD_PATTERN = Pattern.compile("^##\\s+(.+?)\\s*$");
    private static final Pattern ARTICLE_PATTERN = Pattern.compile("^###\\s+(.+?)\\s+\\[\\[(N\\d{2})]]\\s*$");
    private static final Pattern FIELD_PATTERN = Pattern.compile(
            "^[-*+]\\s+(?:\\*\\*)?([^：:]+?)(?:\\*\\*)?\\s*[：:]\\s*(.*?)\\s*$");
    private static final Pattern LEGACY_FIELD_PATTERN = Pattern.compile(
            "^(?:[-*+]\\s+|#{1,6}\\s+)?(?:\\*\\*)?(核心事实|关键细节|影响分析)"
                    + "(?:\\*\\*)?(?:\\s*[：:]\\s*(.*))?\\s*$");

    public ParsedDocument parse(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalArgumentException("AI Markdown 不能为空");
        }
        String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n').trim();
        List<String> lines = Arrays.asList(normalized.split("\n", -1));
        if (lines.isEmpty() || !OVERVIEW_PATTERN.matcher(lines.get(0).trim()).matches()) {
            throw new IllegalArgumentException("AI Markdown 必须以“# 今日概览”开始");
        }

        StringBuilder overview = new StringBuilder();
        List<MutableSection> mutableSections = new ArrayList<>();
        Set<String> sectionNames = new HashSet<>();
        Set<String> citations = new HashSet<>();
        MutableSection currentSection = null;
        MutableArticle currentArticle = null;
        boolean boardStarted = false;
        boolean skippingLegacyField = false;

        for (int index = 1; index < lines.size(); index++) {
            String line = lines.get(index).trim();
            if (line.isEmpty()) {
                continue;
            }

            Matcher boardMatcher = BOARD_PATTERN.matcher(line);
            if (boardMatcher.matches()) {
                String boardName = boardMatcher.group(1).trim();
                if (!BOARD_NAMES.contains(boardName)) {
                    throw new IllegalArgumentException("AI Markdown 包含未知新闻板块：" + boardName);
                }
                if (!sectionNames.add(boardName)) {
                    throw new IllegalArgumentException("AI Markdown 重复输出新闻板块：" + boardName);
                }
                currentSection = new MutableSection(boardName);
                mutableSections.add(currentSection);
                currentArticle = null;
                boardStarted = true;
                skippingLegacyField = false;
                continue;
            }

            Matcher articleMatcher = ARTICLE_PATTERN.matcher(line);
            if (articleMatcher.matches()) {
                if (currentSection == null) {
                    throw new IllegalArgumentException("AI 新闻必须归属于固定新闻板块");
                }
                String citation = articleMatcher.group(2);
                if (!citations.add(citation)) {
                    throw new IllegalArgumentException("AI Markdown 重复使用引用标记：" + citation);
                }
                currentArticle = new MutableArticle(articleMatcher.group(1).trim(), citation);
                currentSection.articles.add(currentArticle);
                skippingLegacyField = false;
                continue;
            }

            Matcher fieldMatcher = FIELD_PATTERN.matcher(line);
            if (fieldMatcher.matches()) {
                if (currentArticle == null) {
                    throw new IllegalArgumentException("AI Markdown 字段缺少对应新闻标题");
                }
                String label = fieldMatcher.group(1).replace("*", "").trim();
                if (isLegacyField(label)) {
                    skippingLegacyField = true;
                    continue;
                }
                if (!FIELD_NAMES.contains(label)) {
                    throw new IllegalArgumentException("AI Markdown 包含未知新闻字段：" + label);
                }
                if (currentArticle.fields.put(label, fieldMatcher.group(2).trim()) != null) {
                    throw new IllegalArgumentException("AI Markdown 重复输出新闻字段：" + label);
                }
                skippingLegacyField = false;
                continue;
            }

            Matcher legacyFieldMatcher = LEGACY_FIELD_PATTERN.matcher(line);
            if (legacyFieldMatcher.matches()) {
                skippingLegacyField = true;
                continue;
            }

            if (!boardStarted) {
                if (overview.length() > 0) {
                    overview.append('\n');
                }
                overview.append(line);
                continue;
            }
            if (skippingLegacyField) {
                continue;
            }
            throw new IllegalArgumentException("AI Markdown 包含无法识别的内容：" + line);
        }

        List<ParsedSection> sections = mutableSections.stream()
                .map(section -> new ParsedSection(section.name, section.articles.stream()
                        .map(article -> new ParsedArticle(article.title, article.citation, article.fields))
                        .toList()))
                .toList();
        return new ParsedDocument(overview.toString().trim(), sections);
    }

    private boolean isLegacyField(String label) {
        return Set.of("核心事实", "关键细节", "影响分析").contains(label);
    }

    public ParsedDocument sortArticles(ParsedDocument document, java.util.Comparator<ParsedArticle> comparator) {
        List<ParsedSection> sortedSections = document.sections().stream()
                .map(section -> {
                    List<ParsedArticle> articles = new ArrayList<>(section.articles());
                    articles.sort(comparator);
                    return new ParsedSection(section.name(), articles);
                })
                .toList();
        return new ParsedDocument(document.overview(), sortedSections);
    }

    public static final class ParsedDocument {

        private final String overview;
        private final List<ParsedSection> sections;

        private ParsedDocument(String overview, List<ParsedSection> sections) {
            this.overview = overview;
            this.sections = List.copyOf(sections);
        }

        public String overview() {
            return overview;
        }

        public List<ParsedSection> sections() {
            return sections;
        }

        public String toMarkdown() {
            StringBuilder markdown = new StringBuilder("# 今日概览\n\n");
            if (!overview.isBlank()) {
                markdown.append(overview).append("\n\n");
            }
            for (int sectionIndex = 0; sectionIndex < sections.size(); sectionIndex++) {
                ParsedSection section = sections.get(sectionIndex);
                markdown.append("## ").append(section.name()).append('\n');
                for (ParsedArticle article : section.articles()) {
                    markdown.append("\n### ").append(article.title()).append(" [[")
                            .append(article.citation()).append("]]\n");
                    for (String fieldName : FIELD_NAMES) {
                        if (article.fields().containsKey(fieldName)) {
                            markdown.append("- ").append(fieldName).append("：")
                                    .append(article.fields().get(fieldName)).append('\n');
                        }
                    }
                }
                if (sectionIndex < sections.size() - 1) {
                    markdown.append('\n');
                }
            }
            return markdown.toString().trim();
        }
    }

    public static final class ParsedSection {

        private final String name;
        private final List<ParsedArticle> articles;

        private ParsedSection(String name, List<ParsedArticle> articles) {
            this.name = name;
            this.articles = List.copyOf(articles);
        }

        public String name() {
            return name;
        }

        public List<ParsedArticle> articles() {
            return articles;
        }
    }

    public static final class ParsedArticle {

        private final String title;
        private final String citation;
        private final Map<String, String> fields;

        private ParsedArticle(String title, String citation, Map<String, String> fields) {
            this.title = title;
            this.citation = citation;
            this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }

        public String title() {
            return title;
        }

        public String citation() {
            return citation;
        }

        public Map<String, String> fields() {
            return fields;
        }

        public String field(String name) {
            return fields.getOrDefault(name, "");
        }
    }

    private static final class MutableSection {

        private final String name;
        private final List<MutableArticle> articles = new ArrayList<>();

        private MutableSection(String name) {
            this.name = name;
        }
    }

    private static final class MutableArticle {

        private final String title;
        private final String citation;
        private final Map<String, String> fields = new LinkedHashMap<>();

        private MutableArticle(String title, String citation) {
            this.title = title;
            this.citation = citation;
        }
    }
}
