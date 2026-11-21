package leon406;

import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.plaf.synth.SynthComboBoxUI;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.JTextComponent;
import javax.swing.text.PlainDocument;
import javax.swing.tree.DefaultTreeCellRenderer;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 翻译核心：加载 cn.txt 词库，做字面量 + 正则匹配。
 * 词库格式：<code>原文&lt;TAB&gt;译文</code>，以 # 开头为注释；
 * 译文中含 $1 之类的反向引用、或原文含 (?i) 时按正则处理。
 */
public final class CnTranslator {

    private static final Pattern REGEX_MARK = Pattern.compile(".*\\$\\d.*");
    private static final Pattern CHINESE = Pattern.compile("[\\u4e00-\\u9fa5]+");
    private static final String LANG = "cn";

    private static final Map<String, String> LITERAL = new ConcurrentHashMap<>(8192);
    private static final Map<Pattern, String> REGEXP = Collections.synchronizedMap(new ConcurrentHashMap<>(1024));
    private static final List<Pattern> WHITE = Collections.synchronizedList(new ArrayList<>(128));
    private static final Map<String, String> CACHE = Collections.synchronizedMap(new LruCache<>(8192));
    private static final Map<String, String> WHITE_CACHE = Collections.synchronizedMap(new LruCache<>(1024));

    private static final File DIR = new File(System.getProperty("user.dir", "."));
    private static final boolean DEBUG = new File(DIR, "debug").exists();
    private static FileWriter UNTRANSLATED;
    private static FileWriter TRANSLATED;

    static {
        load();
    }

    private CnTranslator() {
    }

    private static void load() {
        try {
            File logDir = new File(DIR, "log");
            //noinspection ResultOfMethodCallIgnored
            logDir.mkdirs();
            UNTRANSLATED = new FileWriter(new File(logDir, "unTranslate.txt"), true);
            TRANSLATED = new FileWriter(new File(logDir, "translated.txt"), true);
        } catch (IOException e) {
            System.err.println("[burp-cn] cannot create log dir: " + e);
        }

        readWhiteList(CnTranslator.class.getResourceAsStream("/white.txt"));
        try {
            readWhiteList(new FileInputStream(new File(DIR, "white.txt")));
        } catch (IOException ignored) {
            // 外置白名单是可选的
        }

        readDictionary(CnTranslator.class.getResourceAsStream("/cn.txt"));
        // 外置词库：安装目录下 cn*.txt，可覆盖内置译文
        File[] extras = DIR.listFiles(f -> f.isFile()
                && f.getName().startsWith(LANG) && f.getName().endsWith(".txt"));
        if (extras != null) {
            for (File f : extras) {
                try {
                    System.out.println("[burp-cn] external dictionary: " + f.getName());
                    readDictionary(new FileInputStream(f));
                } catch (IOException e) {
                    System.err.println("[burp-cn] external dictionary failed: " + f + ": " + e);
                }
            }
        }
        System.out.println("[burp-cn] dictionary ready: literal=" + LITERAL.size()
                + " regex=" + REGEXP.size() + " white=" + WHITE.size()
                + (DEBUG ? " (debug)" : ""));
    }

    private static void readDictionary(InputStream is) {
        if (is == null) {
            return;
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] split = line.split("\t", 2);
                if (split.length != 2) {
                    continue;
                }
                if (REGEX_MARK.matcher(split[1]).matches() || split[0].contains("(?i)")) {
                    try {
                        REGEXP.put(Pattern.compile("(?m)^" + split[0] + "$"),
                                split[1].replace("\"", "\\\""));
                    } catch (PatternSyntaxException e) {
                        System.err.println("[burp-cn] invalid regex: " + split[0]);
                    }
                } else {
                    LITERAL.put(split[0], split[1]);
                }
            }
        } catch (IOException e) {
            System.err.println("[burp-cn] dictionary load failed: " + e);
        }
    }

    private static void readWhiteList(InputStream is) {
        if (is == null) {
            return;
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                try {
                    WHITE.add(Pattern.compile(line));
                } catch (PatternSyntaxException ignored) {
                    // 跳过非法规则
                }
            }
        } catch (IOException e) {
            System.err.println("[burp-cn] whitelist load failed: " + e);
        }
    }

    /** 带组件上下文的入口：可编辑文本、文本域等不翻译，避免影响用户输入。 */
    public static String translate(Object src, String lang, String str) {
        if (src == null) {
            return str;
        }
        Class<?> clazz = src.getClass();
        boolean editable = src instanceof JTextComponent && ((JTextComponent) src).isEditable();
        // 单行输入框（搜索框占位符、过滤框）允许翻译；多行编辑区一律跳过，避免动到用户内容
        boolean singleLineField = src instanceof JTextField;
        if (src instanceof PlainDocument
                || editable && !singleLineField
                || src instanceof JTextArea
                || src instanceof DefaultStyledDocument && !clazz.getName().contains("DefaultStyledDocument")
                || src instanceof DefaultTableCellRenderer
                && !clazz.getName().contains("DefaultTableCellHeaderRenderer")
                || clazz.equals(SynthComboBoxUI.class)) {
            return str;
        }
        return translate(lang, str);
    }

    public static String translate(String lang, String str) {
        if (str == null || str.length() < 2) {
            return str;
        }
        if (CHINESE.matcher(str).find()) {
            return str;
        }
        if (WHITE_CACHE.containsKey(str)) {
            return str;
        }
        String cached = CACHE.get(str);
        if (cached != null) {
            return cached;
        }
        if (isWhite(str)) {
            WHITE_CACHE.put(str, str);
            return str;
        }

        String text = str;
        if (str.startsWith("<html>") && (str.contains("\n") || str.contains("\r\n"))) {
            text = str.replaceAll("\r\n|\n", " ");
        }

        StringBuilder sb = new StringBuilder();
        if (text.contains(" | ")) {
            for (String part : text.split(" \\| ")) {
                if (sb.length() > 0) {
                    sb.append(" | ");
                }
                if (part.contains(";")) {
                    String[] pieces = part.split("; *");
                    for (int i = 0; i < pieces.length; i++) {
                        if (i != 0) {
                            sb.append("; ");
                        }
                        sb.append(translateOne(pieces[i].trim()));
                    }
                } else {
                    sb.append(translateOne(part));
                }
            }
        } else {
            for (String part : text.split("\r\n|\n")) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(translateOne(part));
            }
        }

        String result = sb.toString();
        if (DEBUG && TRANSLATED != null) {
            write(TRANSLATED, str + "===" + result + "\n");
        }
        if (str.equals(result) && UNTRANSLATED != null) {
            write(UNTRANSLATED, str + "\n");
        }
        CACHE.put(str, result);
        return result;
    }

    private static boolean isWhite(String str) {
        synchronized (WHITE) {
            for (Pattern p : WHITE) {
                if (p.matcher(str).matches()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String translateOne(String str) {
        String hit = LITERAL.get(str.trim());
        if (hit != null) {
            return hit;
        }
        String current = str;
        synchronized (REGEXP) {
            for (Map.Entry<Pattern, String> entry : REGEXP.entrySet()) {
                Matcher matcher = entry.getKey().matcher(current);
                if (matcher.matches()) {
                    current = matcher.replaceAll(entry.getValue());
                }
            }
        }
        return current;
    }

    private static void write(FileWriter writer, String msg) {
        try {
            writer.write(msg);
            writer.flush();
        } catch (IOException ignored) {
            // 日志失败不影响主流程
        }
    }
}
