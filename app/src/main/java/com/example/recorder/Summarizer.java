package com.example.recorder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地抽取式中文摘要（无需联网、无需大模型）：
 * 按标点分句 → 用词频(二元字组) + 位置 + 长度 加权打分 → 取最重要的若干句作为「要点」，
 * 按原文顺序拼接成「概要」。这是启发式摘要，不是 AI 级「理解」，但完全离线、即时、隐私好。
 */
public class Summarizer {

    public static class Summary {
        public final String summary;       // 概要（拼接后的连贯文字）
        public final List<String> keyPoints; // 要点（重要句子列表）
        public Summary(String summary, List<String> keyPoints) {
            this.summary = summary;
            this.keyPoints = keyPoints;
        }
    }

    /** 按中文/英文标点切句 */
    public static List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            sb.append(c);
            if ("。！？!?；;\n".indexOf(c) >= 0) {
                String s = sb.toString().trim();
                if (!s.isEmpty()) out.add(s);
                sb.setLength(0);
            }
        }
        if (sb.length() > 0) {
            String s = sb.toString().trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    public static Summary summarize(String text, int topN) {
        List<String> sents = splitSentences(text);
        if (sents.isEmpty()) return new Summary("", new ArrayList<>());
        if (sents.size() <= topN) {
            return new Summary(String.join("。", sents) + "。", new ArrayList<>(sents));
        }

        // 统计每个二元字组的词频（中文没有空格，用相邻两字近似「词」）
        Map<String, Integer> freq = new HashMap<>();
        for (String s : sents) {
            for (String g : bigrams(s)) freq.put(g, freq.getOrDefault(g, 0) + 1);
        }

        int n = sents.size();
        double[] score = new double[n];
        for (int i = 0; i < n; i++) {
            List<String> grams = bigrams(sents.get(i));
            double sc = 0;
            for (String g : grams) sc += freq.getOrDefault(g, 0);
            sc = grams.isEmpty() ? 0 : sc / grams.size();
            // 位置权重：越靠近开头/结尾越重要
            double pos = 1.0 - Math.abs((i + 1) - (n / 2.0)) / (n / 2.0 + 1);
            // 长度因子：太短信息少、太长可能是废话，适中最好
            int len = sents.get(i).length();
            double lenFac = len < 8 ? 0.5 : (len > 80 ? 0.8 : 1.0);
            score[i] = sc * (0.5 + 0.5 * pos) * lenFac;
        }

        // 取分数最高的 topN 句，再按原文顺序排好
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> Double.compare(score[b], score[a]));

        List<String> top = new ArrayList<>();
        for (int k = 0; k < Math.min(topN, n); k++) top.add(sents.get(idx[k]));
        final List<String> ordered = new ArrayList<>(sents);
        top.sort((a, b) -> ordered.indexOf(a) - ordered.indexOf(b));

        String summaryText = String.join("。", top) + "。";
        return new Summary(summaryText, top);
    }

    private static List<String> bigrams(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isLetterOrDigit(c) || isHan(c)) sb.append(c);
        }
        String t = sb.toString();
        List<String> g = new ArrayList<>();
        if (t.length() == 1) { g.add(t); return g; }
        for (int i = 0; i + 1 < t.length(); i++) g.add(t.substring(i, i + 2));
        return g;
    }

    private static boolean isHan(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }
}
