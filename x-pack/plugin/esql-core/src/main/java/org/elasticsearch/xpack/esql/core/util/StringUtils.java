/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.esql.core.util;

import org.apache.lucene.document.InetAddressPoint;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.search.spell.LevenshteinDistance;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.CollectionUtil;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.network.InetAddresses;
import org.elasticsearch.core.Tuple;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.transport.RemoteClusterAware;
import org.elasticsearch.xcontent.ToXContent;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentFactory;
import org.elasticsearch.xpack.esql.core.InvalidArgumentException;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

import static java.util.stream.Collectors.toList;
import static org.elasticsearch.transport.RemoteClusterAware.buildRemoteIndexName;
import static org.elasticsearch.xpack.esql.core.util.NumericUtils.isUnsignedLong;

public final class StringUtils {

    private StringUtils() {}

    public static final String EMPTY = "";
    public static final String NEW_LINE = "\n";
    public static final String SQL_WILDCARD = "%";
    public static final String WILDCARD = "*";
    public static final String EXCLUSION = "-";

    private static final String[] INTEGER_ORDINALS = new String[] { "th", "st", "nd", "rd", "th", "th", "th", "th", "th", "th" };

    private static final String INVALID_REGEX_SEQUENCE = "Invalid sequence - escape character is not followed by special wildcard char";

    // CamelCase to camel_case (and isNaN to is_nan)
    public static String camelCaseToUnderscore(String string) {
        if (Strings.hasText(string) == false) {
            return EMPTY;
        }
        StringBuilder sb = new StringBuilder();
        String s = string.trim();

        boolean previousCharWasUp = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isAlphabetic(ch)) {
                if (Character.isUpperCase(ch)) {
                    // append `_` when encountering a capital after a small letter, but only if not the last letter.
                    if (i > 0 && i < s.length() - 1 && previousCharWasUp == false) {
                        sb.append("_");
                    }
                    previousCharWasUp = true;
                } else {
                    previousCharWasUp = (ch == '_');
                }
            } else {
                previousCharWasUp = true;
            }
            sb.append(ch);
        }
        return sb.toString().toUpperCase(Locale.ROOT);
    }

    // CAMEL_CASE to camelCase
    public static String underscoreToLowerCamelCase(String string) {
        if (Strings.hasText(string) == false) {
            return EMPTY;
        }
        StringBuilder sb = new StringBuilder();
        String s = string.trim().toLowerCase(Locale.ROOT);

        boolean previousCharWasUnderscore = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '_') {
                previousCharWasUnderscore = true;
            } else {
                if (previousCharWasUnderscore) {
                    sb.append(Character.toUpperCase(ch));
                    previousCharWasUnderscore = false;
                } else {
                    sb.append(ch);
                }
            }
        }
        return sb.toString();
    }

    // % -> .*
    // _ -> .
    // escape character - can be 0 (in which case no regex gets escaped) or
    // should be followed by % or _ (otherwise an exception is thrown)
    public static String likeToJavaPattern(String pattern, char escape) {
        StringBuilder regex = new StringBuilder(pattern.length() + 4);

        boolean escaped = false;
        regex.append('^');
        for (int i = 0; i < pattern.length(); i++) {
            char curr = pattern.charAt(i);
            if (escaped == false && (curr == escape) && escape != 0) {
                escaped = true;
                if (i + 1 == pattern.length()) {
                    throw new InvalidArgumentException(INVALID_REGEX_SEQUENCE);
                }
            } else {
                switch (curr) {
                    case '%' -> regex.append(escaped ? SQL_WILDCARD : ".*");
                    case '_' -> regex.append(escaped ? "_" : ".");
                    default -> {
                        if (escaped) {
                            throw new InvalidArgumentException(INVALID_REGEX_SEQUENCE);
                        }
                        // escape special regex characters
                        switch (curr) {
                            case '\\', '^', '$', '.', '*', '?', '+', '|', '(', ')', '[', ']', '{', '}' -> regex.append('\\');
                        }
                        regex.append(curr);
                    }
                }
                escaped = false;
            }
        }
        regex.append('$');

        return regex.toString();
    }

    // * -> .*
    // ? -> .
    // escape character - can be 0 (in which case no regex gets escaped) or
    // should be followed by * or ? or the escape character itself (otherwise an exception is thrown).
    // Using * or ? as escape characters should be avoided because it will make it impossible to enter them as literals
    public static String wildcardToJavaPattern(String pattern, char escape) {
        StringBuilder regex = new StringBuilder(pattern.length() + 4);

        boolean escaped = false;
        regex.append('^');
        for (int i = 0; i < pattern.length(); i++) {
            char curr = pattern.charAt(i);
            if (escaped == false && (curr == escape) && escape != 0) {
                escaped = true;
                if (i + 1 == pattern.length()) {
                    throw new InvalidArgumentException(INVALID_REGEX_SEQUENCE);
                }
            } else {
                switch (curr) {
                    case '*' -> regex.append(escaped ? "\\*" : ".*");
                    case '?' -> regex.append(escaped ? "\\?" : ".");
                    default -> {
                        if (escaped && escape != curr) {
                            throw new InvalidArgumentException(INVALID_REGEX_SEQUENCE);
                        }
                        // escape special regex characters
                        switch (curr) {
                            case '\\', '^', '$', '.', '*', '?', '+', '|', '(', ')', '[', ']', '{', '}' -> regex.append('\\');
                        }
                        regex.append(curr);
                    }
                }
                escaped = false;
            }
        }
        regex.append('$');

        return regex.toString();
    }

    /**
     * Translates a Lucene wildcard pattern to a Lucene RegExp one.
     * Note: all RegExp "optional" characters are escaped too (allowing the use of the {@code RegExp.ALL} flag).
     * @param wildcard Lucene wildcard pattern
     * @return Lucene RegExp pattern
     */
    public static String luceneWildcardToRegExp(String wildcard) {
        StringBuilder regex = new StringBuilder();

        for (int i = 0, wcLen = wildcard.length(); i < wcLen; i++) {
            char c = wildcard.charAt(i); // this will work chunking through Unicode as long as all values matched are ASCII
            switch (c) {
                case WildcardQuery.WILDCARD_STRING -> regex.append(".*");
                case WildcardQuery.WILDCARD_CHAR -> regex.append(".");
                case WildcardQuery.WILDCARD_ESCAPE -> {
                    if (i + 1 < wcLen) {
                        // consume the wildcard escaping, consider the next char
                        char next = wildcard.charAt(i + 1);
                        i++;
                        switch (next) {
                            case WildcardQuery.WILDCARD_STRING, WildcardQuery.WILDCARD_CHAR, WildcardQuery.WILDCARD_ESCAPE ->
                                // escape `*`, `.`, `\`, since these are special chars in RegExp as well
                                regex.append("\\");
                            // default: unnecessary escaping -- just ignore the escaping
                        }
                        regex.append(next);
                    } else {
                        // "else fallthru, lenient parsing with a trailing \" -- according to WildcardQuery#toAutomaton
                        regex.append("\\\\");
                    }
                }
                // reserved RegExp characters
                case '"', '$', '(', ')', '+', '.', '[', ']', '^', '{', '|', '}' -> regex.append("\\").append(c);
                // reserved optional RegExp characters
                case '#', '&', '<', '>' -> regex.append("\\").append(c);
                default -> regex.append(c);
            }
        }

        return regex.toString();
    }

    /**
     * Translates a like pattern to a Lucene wildcard.
     * This methods pays attention to the custom escape char which gets converted into \ (used by Lucene).
     * <pre>
     * % -&gt; *
     * _ -&gt; ?
     * escape character - can be 0 (in which case no regex gets escaped) or should be followed by
     * % or _ (otherwise an exception is thrown)
     * </pre>
     */
    public static String likeToLuceneWildcard(String pattern, char escape) {
        StringBuilder wildcard = new StringBuilder(pattern.length() + 4);

        boolean escaped = false;
        for (int i = 0; i < pattern.length(); i++) {
            char curr = pattern.charAt(i);

            if (escaped == false && (curr == escape) && escape != 0) {
                if (i + 1 == pattern.length()) {
                    throw new InvalidArgumentException(INVALID_REGEX_SEQUENCE);
                }
                escaped = true;
            } else {
                switch (curr) {
                    case '%' -> wildcard.append(escaped ? SQL_WILDCARD : WILDCARD);
                    case '_' -> wildcard.append(escaped ? "_" : "?");
                    default -> {
                        if (escaped) {
                            throw new InvalidArgumentException(INVALID_REGEX_SEQUENCE);
                        }
                        // escape special regex characters
                        switch (curr) {
                            case '\\', '*', '?' -> wildcard.append('\\');
                        }
                        wildcard.append(curr);
                    }
                }
                escaped = false;
            }
        }
        return wildcard.toString();
    }

    /**
     * Translates a like pattern to pattern for ES index name expression resolver.
     *
     * Note the resolver only supports * (not ?) and has no notion of escaping. This is not really an issue since we don't allow *
     * anyway in the pattern.
     */
    public static String likeToIndexWildcard(String pattern, char escape) {
        StringBuilder wildcard = new StringBuilder(pattern.length() + 4);

        boolean escaped = false;
        for (int i = 0; i < pattern.length(); i++) {
            char curr = pattern.charAt(i);

            if (escaped == false && (curr == escape) && escape != 0) {
                if (i + 1 == pattern.length()) {
                    throw new InvalidArgumentException(INVALID_REGEX_SEQUENCE);
                }
                escaped = true;
            } else {
                switch (curr) {
                    case '%' -> wildcard.append(escaped ? SQL_WILDCARD : WILDCARD);
                    case '_' -> wildcard.append(escaped ? "_" : "*");
                    default -> {
                        if (escaped) {
                            throw new InvalidArgumentException(INVALID_REGEX_SEQUENCE);
                        }
                        // the resolver doesn't support escaping...
                        wildcard.append(curr);
                    }
                }
                escaped = false;
            }
        }
        return wildcard.toString();
    }

    public static String likeToUnescaped(String pattern, char escape) {
        StringBuilder wildcard = new StringBuilder(pattern.length());

        boolean escaped = false;
        for (int i = 0; i < pattern.length(); i++) {
            char curr = pattern.charAt(i);

            if (escaped == false && curr == escape && escape != 0) {
                escaped = true;
            } else {
                if (escaped && (curr == '%' || curr == '_' || curr == escape)) {
                    wildcard.append(curr);
                } else {
                    if (escaped) {
                        wildcard.append(escape);
                    }
                    wildcard.append(curr);
                }
                escaped = false;
            }
        }
        // corner-case when the escape char is the last char
        if (escaped) {
            wildcard.append(escape);
        }
        return wildcard.toString();
    }

    public static String toString(SearchSourceBuilder source) {
        try (XContentBuilder builder = XContentFactory.jsonBuilder().prettyPrint().humanReadable(true)) {
            source.toXContent(builder, ToXContent.EMPTY_PARAMS);
            return Strings.toString(builder);
        } catch (IOException e) {
            throw new RuntimeException("error rendering", e);
        }
    }

    public static List<String> findSimilar(String match, Iterable<String> potentialMatches) {
        LevenshteinDistance ld = new LevenshteinDistance();
        List<Tuple<Float, String>> scoredMatches = new ArrayList<>();
        for (String potentialMatch : potentialMatches) {
            float distance = ld.getDistance(match, potentialMatch);
            if (distance >= 0.5f) {
                scoredMatches.add(new Tuple<>(distance, potentialMatch));
            }
        }
        CollectionUtil.timSort(scoredMatches, (a, b) -> b.v1().compareTo(a.v1()));
        return scoredMatches.stream().map(a -> a.v2()).collect(toList());
    }

    public static double parseDouble(String string) throws InvalidArgumentException {
        double value;
        try {
            value = Double.parseDouble(string);
        } catch (NumberFormatException nfe) {
            throw new InvalidArgumentException(nfe, "Cannot parse number [{}]", string);
        }

        if (Double.isInfinite(value)) {
            throw new InvalidArgumentException("Number [{}] is too large", string);
        }
        if (Double.isNaN(value)) {
            throw new InvalidArgumentException("[{}] cannot be parsed as a number (NaN)", string);
        }
        return value;
    }

    public static long parseLong(String string) throws InvalidArgumentException {
        try {
            return Long.parseLong(string);
        } catch (NumberFormatException nfe) {
            try {
                BigInteger bi = new BigInteger(string);
                try {
                    bi.longValueExact();
                } catch (ArithmeticException ae) {
                    throw new InvalidArgumentException("Number [{}] is too large", string);
                }
            } catch (NumberFormatException ex) {
                // parsing fails, go through
            }
            throw new InvalidArgumentException("Cannot parse number [{}]", string);
        }
    }

    public static Number parseIntegral(String string) throws InvalidArgumentException {
        BigInteger bi;
        try {
            bi = new BigInteger(string);
        } catch (NumberFormatException ex) {
            throw new InvalidArgumentException(ex, "Cannot parse number [{}]", string);
        }
        if (bi.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            if (isUnsignedLong(bi) == false) {
                throw new InvalidArgumentException("Number [{}] is too large", string);
            }
            return bi;
        }
        if (bi.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0) {
            throw new InvalidArgumentException("Magnitude of negative number [{}] is too large", string);
        }
        // try to downsize to int if possible (since that's the most common type)
        if (bi.intValue() == bi.longValue()) { // ternary operator would always promote to Long
            return bi.intValueExact();
        } else {
            return bi.longValueExact();
        }
    }

    public static BytesRef parseIP(String string) {
        var inetAddress = InetAddresses.forString(string);
        return new BytesRef(InetAddressPoint.encode(inetAddress));
    }

    public static String ordinal(int i) {
        return switch (i % 100) {
            case 11, 12, 13 -> i + "th";
            default -> i + INTEGER_ORDINALS[i % 10];
        };
    }

    public static Tuple<String, String> splitQualifiedIndex(String indexName) {
        String[] split = RemoteClusterAware.splitIndexName(indexName);
        return Tuple.tuple(split[0], split[1]);
    }

    public static String qualifyAndJoinIndices(String cluster, String[] indices) {
        StringJoiner sj = new StringJoiner(",");
        for (String index : indices) {
            sj.add(cluster != null ? buildRemoteIndexName(cluster, index) : index);
        }
        return sj.toString();
    }

    public static boolean isQualified(String indexWildcard) {
        return RemoteClusterAware.isRemoteIndexName(indexWildcard);
    }

    public static boolean isInteger(String value) {
        for (char c : value.trim().toCharArray()) {
            if (isDigit(c) == false) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isUnderscore(char c) {
        return c == '_';
    }

    private static boolean isLetterOrDigitOrUnderscore(char c) {
        return isLetter(c) || isDigit(c) || isUnderscore(c);
    }

    private static boolean isLetterOrUnderscore(char c) {
        return isLetter(c) || isUnderscore(c);
    }

    public static boolean isValidParamName(String value) {
        // A valid name starts with a letter or _
        if (isLetterOrUnderscore(value.charAt(0)) == false) {
            return false;
        }
        // contain only letter, digit or _
        for (char c : value.toCharArray()) {
            if (isLetterOrDigitOrUnderscore(c) == false) {
                return false;
            }
        }
        return true;
    }
}
