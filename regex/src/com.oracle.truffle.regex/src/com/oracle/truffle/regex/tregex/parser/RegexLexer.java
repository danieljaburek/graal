/*
 * Copyright (c) 2018, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.regex.tregex.parser;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.regex.RegexFlags;
import com.oracle.truffle.regex.RegexSource;
import com.oracle.truffle.regex.RegexSyntaxException;
import com.oracle.truffle.regex.charset.CodePointSet;
import com.oracle.truffle.regex.charset.CodePointSetAccumulator;
import com.oracle.truffle.regex.charset.Constants;
import com.oracle.truffle.regex.charset.UnicodeProperties;
import com.oracle.truffle.regex.errors.ErrorMessages;
import com.oracle.truffle.regex.util.TBitSet;

import java.math.BigInteger;
import java.util.Map;

public final class RegexLexer extends BaseLexer {

    private static final TBitSet PREDEFINED_CHAR_CLASSES = TBitSet.valueOf('D', 'S', 'W', 'd', 's', 'w');
    private static final CodePointSet ID_START = UnicodeProperties.getProperty("ID_Start");
    private static final CodePointSet ID_CONTINUE = UnicodeProperties.getProperty("ID_Continue");
    private final RegexFlags flags;
    private final CodePointSetAccumulator curCharClass = new CodePointSetAccumulator();
    private final CodePointSetAccumulator charClassCaseFoldTmp = new CodePointSetAccumulator();

    public RegexLexer(RegexSource source, RegexFlags flags) {
        super(source);
        this.flags = flags;
    }

    /**
     * Returns the last token's position in the pattern string.
     */
    public int getLastTokenPosition() {
        return curStartIndex;
    }

    private Token charClass(int codePoint) {
        if (flags.isIgnoreCase()) {
            curCharClass.clear();
            curCharClass.appendRange(codePoint, codePoint);
            return charClass(false);
        } else {
            return Token.createCharClass(CodePointSet.create(codePoint), true);
        }
    }

    private Token charClass(CodePointSet codePointSet) {
        if (flags.isIgnoreCase()) {
            curCharClass.clear();
            curCharClass.addSet(codePointSet);
            return charClass(false);
        } else {
            return Token.createCharClass(codePointSet);
        }
    }

    private Token charClass(boolean invert) {
        boolean wasSingleChar = !invert && curCharClass.matchesSingleChar();
        if (flags.isIgnoreCase()) {
            CaseFoldTable.CaseFoldingAlgorithm caseFolding = flags.isUnicode() ? CaseFoldTable.CaseFoldingAlgorithm.ECMAScriptUnicode : CaseFoldTable.CaseFoldingAlgorithm.ECMAScriptNonUnicode;
            CaseFoldTable.applyCaseFold(curCharClass, charClassCaseFoldTmp, caseFolding);
        }
        CodePointSet cps = curCharClass.toCodePointSet();
        return Token.createCharClass(invert ? cps.createInverse(encoding) : cps, wasSingleChar);
    }

    /* lexer */

    protected Token getNext() throws RegexSyntaxException {
        final char c = consumeChar();
        switch (c) {
            case '.':
                return Token.createCharClass(flags.isDotAll() ? Constants.DOT_ALL : Constants.DOT);
            case '^':
                return Token.createCaret();
            case '$':
                return Token.createDollar();
            case '{':
            case '*':
            case '+':
            case '?':
                return parseQuantifier(c);
            case '}':
                if (flags.isUnicode()) {
                    // In ECMAScript regular expressions, syntax characters such as '}' and ']'
                    // cannot be used as atomic patterns. However, Annex B relaxes this condition
                    // and allows the use of unmatched '}' and ']', which then match themselves.
                    // Neverthelesss, in Unicode mode, we should still be strict.
                    throw syntaxError(ErrorMessages.UNMATCHED_RIGHT_BRACE);
                }
                return charClass(c);
            case '|':
                return Token.createAlternation();
            case '(':
                return parseGroupBegin();
            case ')':
                return Token.createGroupEnd();
            case '[':
                return parseCharClass();
            case ']':
                if (flags.isUnicode()) {
                    throw syntaxError(ErrorMessages.UNMATCHED_RIGHT_BRACKET);
                }
                return charClass(c);
            case '\\':
                return parseEscape();
            default:
                if (flags.isUnicode() && Character.isHighSurrogate(c)) {
                    return charClass(finishSurrogatePair(c));
                }
                return charClass(c);
        }
    }

    private Token parseEscape() throws RegexSyntaxException {
        if (atEnd()) {
            throw syntaxError(ErrorMessages.ENDS_WITH_UNFINISHED_ESCAPE_SEQUENCE);
        }
        final char c = consumeChar();
        if ('1' <= c && c <= '9') {
            final int restoreIndex = index;
            final int backRefNumber = parseInteger(c - '0');
            if (backRefNumber < numberOfCaptureGroups()) {
                return Token.createBackReference(backRefNumber);
            } else if (flags.isUnicode()) {
                throw syntaxError(ErrorMessages.MISSING_GROUP_FOR_BACKREFERENCE);
            }
            index = restoreIndex;
        }
        switch (c) {
            case 'k':
                if (flags.isUnicode() || hasNamedCaptureGroups()) {
                    if (atEnd()) {
                        throw syntaxError(ErrorMessages.ENDS_WITH_UNFINISHED_ESCAPE_SEQUENCE);
                    }
                    if (consumeChar() != '<') {
                        throw syntaxError(ErrorMessages.MISSING_GROUP_NAME);
                    }
                    String groupName = parseGroupName();
                    // backward reference
                    if (namedCaptureGroups != null && namedCaptureGroups.containsKey(groupName)) {
                        return Token.createBackReference(namedCaptureGroups.get(groupName));
                    }
                    // possible forward reference
                    Map<String, Integer> allNamedCaptureGroups = getNamedCaptureGroups();
                    if (allNamedCaptureGroups != null && allNamedCaptureGroups.containsKey(groupName)) {
                        return Token.createBackReference(allNamedCaptureGroups.get(groupName));
                    }
                    throw syntaxError(ErrorMessages.MISSING_GROUP_FOR_BACKREFERENCE);
                } else {
                    return charClass(c);
                }
            case 'b':
                return Token.createWordBoundary();
            case 'B':
                return Token.createNonWordBoundary();
            default:
                // Here we differentiate the case when parsing one of the six basic pre-defined
                // character classes (\w, \W, \d, \D, \s, \S) and Unicode character property
                // escapes. Both result in sets of characters, but in the former case, we can skip
                // the case-folding step in the `charClass` method and call `Token::createCharClass`
                // directly.
                if (isPredefCharClass(c)) {
                    return Token.createCharClass(parsePredefCharClass(c));
                } else if (flags.isUnicode() && (c == 'p' || c == 'P')) {
                    return charClass(parseUnicodeCharacterProperty(c == 'P'));
                } else {
                    return charClass(parseEscapeChar(c, false));
                }
        }
    }

    protected Token parseGroupBegin() throws RegexSyntaxException {
        if (consumingLookahead("?=")) {
            return Token.createLookAheadAssertionBegin(false);
        } else if (consumingLookahead("?!")) {
            return Token.createLookAheadAssertionBegin(true);
        } else if (consumingLookahead("?<=")) {
            return Token.createLookBehindAssertionBegin(false);
        } else if (consumingLookahead("?<!")) {
            return Token.createLookBehindAssertionBegin(true);
        } else if (consumingLookahead("?:")) {
            return Token.createNonCaptureGroupBegin();
        } else if (consumingLookahead("?<")) {
            String groupName = parseGroupName();
            registerNamedCaptureGroup(groupName);
            return Token.createCaptureGroupBegin();
        } else {
            registerCaptureGroup();
            return Token.createCaptureGroupBegin();
        }
    }

    private int parseCodePointInGroupName() throws RegexSyntaxException {
        if (consumingLookahead("\\u")) {
            final int unicodeEscape = parseUnicodeEscapeChar();
            if (unicodeEscape < 0) {
                throw syntaxError(ErrorMessages.INVALID_UNICODE_ESCAPE);
            } else {
                return unicodeEscape;
            }
        }
        if (atEnd()) {
            throw syntaxError(ErrorMessages.UNTERMINATED_GROUP_NAME);
        }
        if (consumingLookahead(">")) {
            return -1;
        }
        final char c = consumeChar();
        return flags.isUnicode() && Character.isHighSurrogate(c) ? finishSurrogatePair(c) : c;
    }

    /**
     * Parse a {@code GroupName}, i.e. {@code <RegExpIdentifierName>}, assuming that the opening
     * {@code <} bracket was already read.
     *
     * @return the StringValue of the {@code RegExpIdentifierName}
     */
    private String parseGroupName() throws RegexSyntaxException {
        StringBuilder groupName = new StringBuilder();
        int codePoint = parseCodePointInGroupName();
        if (codePoint == -1) {
            throw syntaxError(ErrorMessages.EMPTY_GROUP_NAME);
        }
        if (!(ID_START.contains(codePoint) || codePoint == '$' || codePoint == '_')) {
            throw syntaxError(ErrorMessages.INVALID_GROUP_NAME_START);
        }
        groupName.appendCodePoint(codePoint);
        while ((codePoint = parseCodePointInGroupName()) != -1) {
            if (!(ID_CONTINUE.contains(codePoint) || codePoint == '$' || codePoint == '\u200c' || codePoint == '\u200d')) {
                throw syntaxError(ErrorMessages.INVALID_GROUP_NAME_PART);
            }
            groupName.appendCodePoint(codePoint);
        }
        return groupName.toString();
    }

    protected Token countedRepetitionSyntaxError(int resetIndex) throws RegexSyntaxException {
        if (flags.isUnicode()) {
            throw syntaxError(ErrorMessages.INCOMPLETE_QUANTIFIER);
        }
        index = resetIndex;
        return charClass('{');
    }

    private Token parseCharClass() throws RegexSyntaxException {
        final boolean invert = consumingLookahead("^");
        curCharClass.clear();
        while (!atEnd()) {
            final char c = consumeChar();
            if (c == ']') {
                return charClass(invert);
            }
            parseCharClassRange(c);
        }
        throw syntaxError(ErrorMessages.UNMATCHED_LEFT_BRACKET);
    }

    private CodePointSet parseCharClassAtomPredefCharClass(char c) throws RegexSyntaxException {
        if (c == '\\') {
            if (atEnd()) {
                throw syntaxError(ErrorMessages.ENDS_WITH_UNFINISHED_ESCAPE_SEQUENCE);
            }
            if (isEscapeCharClass(curChar())) {
                return parseEscapeCharClass(consumeChar());
            }
        }
        return null;
    }

    private int parseCharClassAtomCodePoint(char c) throws RegexSyntaxException {
        if (c == '\\') {
            assert !atEnd();
            assert !isEscapeCharClass(curChar());
            return parseEscapeChar(consumeChar(), true);
        } else if (flags.isUnicode() && Character.isHighSurrogate(c)) {
            return finishSurrogatePair(c);
        } else {
            return c;
        }
    }

    private void parseCharClassRange(char c) throws RegexSyntaxException {
        CodePointSet firstAtomCC = parseCharClassAtomPredefCharClass(c);
        int firstAtomCP = firstAtomCC == null ? parseCharClassAtomCodePoint(c) : -1;
        if (consumingLookahead("-")) {
            if (atEnd() || lookahead("]")) {
                addCharClassAtom(firstAtomCC, firstAtomCP);
                curCharClass.addRange('-', '-');
            } else {
                char nextC = consumeChar();
                CodePointSet secondAtomCC = parseCharClassAtomPredefCharClass(nextC);
                int secondAtomCP = secondAtomCC == null ? parseCharClassAtomCodePoint(nextC) : -1;
                // Runtime Semantics: CharacterRangeOrUnion(firstAtom, secondAtom)
                if (firstAtomCC != null || secondAtomCC != null) {
                    if (flags.isUnicode()) {
                        throw syntaxError(ErrorMessages.INVALID_CHARACTER_CLASS);
                    } else {
                        addCharClassAtom(firstAtomCC, firstAtomCP);
                        addCharClassAtom(secondAtomCC, secondAtomCP);
                        curCharClass.addRange('-', '-');
                    }
                } else {
                    if (secondAtomCP < firstAtomCP) {
                        throw syntaxError(ErrorMessages.CHAR_CLASS_RANGE_OUT_OF_ORDER);
                    } else {
                        curCharClass.addRange(firstAtomCP, secondAtomCP);
                    }
                }
            }
        } else {
            addCharClassAtom(firstAtomCC, firstAtomCP);
        }
    }

    private void addCharClassAtom(CodePointSet preDefCharClass, int codePoint) {
        if (preDefCharClass != null) {
            curCharClass.addSet(preDefCharClass);
        } else {
            curCharClass.addRange(codePoint, codePoint);
        }
    }

    private CodePointSet parseEscapeCharClass(char c) throws RegexSyntaxException {
        if (isPredefCharClass(c)) {
            return parsePredefCharClass(c);
        } else if (flags.isUnicode() && (c == 'p' || c == 'P')) {
            return parseUnicodeCharacterProperty(c == 'P');
        } else {
            throw CompilerDirectives.shouldNotReachHere();
        }
    }

    // Note that the CodePointSet returned by this function has already been
    // case-folded and negated.
    private CodePointSet parsePredefCharClass(char c) {
        switch (c) {
            case 's':
                if (source.getOptions().isU180EWhitespace()) {
                    return Constants.LEGACY_WHITE_SPACE;
                } else {
                    return Constants.WHITE_SPACE;
                }
            case 'S':
                if (source.getOptions().isU180EWhitespace()) {
                    return Constants.LEGACY_NON_WHITE_SPACE;
                } else {
                    return Constants.NON_WHITE_SPACE;
                }
            case 'd':
                return Constants.DIGITS;
            case 'D':
                return Constants.NON_DIGITS;
            case 'w':
                if (flags.isUnicode() && flags.isIgnoreCase()) {
                    return Constants.WORD_CHARS_UNICODE_IGNORE_CASE;
                } else {
                    return Constants.WORD_CHARS;
                }
            case 'W':
                if (flags.isUnicode() && flags.isIgnoreCase()) {
                    return Constants.NON_WORD_CHARS_UNICODE_IGNORE_CASE;
                } else {
                    return Constants.NON_WORD_CHARS;
                }
            default:
                throw CompilerDirectives.shouldNotReachHere();
        }
    }

    private CodePointSet parseUnicodeCharacterProperty(boolean invert) throws RegexSyntaxException {
        if (!consumingLookahead("{")) {
            throw syntaxError(ErrorMessages.INVALID_UNICODE_PROPERTY);
        }
        int namePos = index;
        while (!atEnd() && curChar() != '}') {
            advance();
        }
        if (!consumingLookahead("}")) {
            throw syntaxError(ErrorMessages.ENDS_WITH_UNFINISHED_UNICODE_PROPERTY);
        }
        try {
            CodePointSet propertySet = encoding.getFullSet().createIntersection(UnicodeProperties.getProperty(pattern.substring(namePos, index - 1)), curCharClass.getTmp());
            return invert ? propertySet.createInverse(encoding) : propertySet;
        } catch (IllegalArgumentException e) {
            throw syntaxError(e.getMessage());
        }
    }

    /**
     * Parse a {@code RegExpUnicodeEscapeSequence}, assuming that the prefix '&#92;u' has already
     * been read.
     *
     * @return the code point of the escaped character, or -1 if the escape was malformed
     */
    private int parseUnicodeEscapeChar() throws RegexSyntaxException {
        if (flags.isUnicode() && consumingLookahead("{")) {
            final int value = parseHex(1, Integer.MAX_VALUE, 0x10ffff, ErrorMessages.INVALID_UNICODE_ESCAPE);
            if (!consumingLookahead("}")) {
                throw syntaxError(ErrorMessages.INVALID_UNICODE_ESCAPE);
            }
            return value;
        } else {
            final int value = parseHex(4, 4, 0xffff, ErrorMessages.INVALID_UNICODE_ESCAPE);
            if (flags.isUnicode() && Character.isHighSurrogate((char) value)) {
                final int resetIndex = index;
                if (consumingLookahead("\\u") && !lookahead("{")) {
                    final char lead = (char) value;
                    final char trail = (char) parseHex(4, 4, 0xffff, ErrorMessages.INVALID_UNICODE_ESCAPE);
                    if (Character.isLowSurrogate(trail)) {
                        return Character.toCodePoint(lead, trail);
                    } else {
                        index = resetIndex;
                    }
                } else {
                    index = resetIndex;
                }
            }
            return value;
        }
    }

    private int parseEscapeChar(char c, boolean inCharClass) throws RegexSyntaxException {
        if (inCharClass && c == 'b') {
            return '\b';
        }
        switch (c) {
            case '0':
                if (flags.isUnicode() && !atEnd() && isDecimal(curChar())) {
                    throw syntaxError(ErrorMessages.INVALID_ESCAPE);
                }
                if (!flags.isUnicode() && !atEnd() && isOctal(curChar())) {
                    return parseOctal(0);
                }
                return '\0';
            case 't':
                return '\t';
            case 'n':
                return '\n';
            case 'v':
                return '\u000B';
            case 'f':
                return '\f';
            case 'r':
                return '\r';
            case 'c':
                if (atEnd()) {
                    retreat();
                    return escapeCharSyntaxError('\\', ErrorMessages.INVALID_CONTROL_CHAR_ESCAPE);
                }
                final char controlLetter = curChar();
                if (!flags.isUnicode() && (isDecimal(controlLetter) || controlLetter == '_') && inCharClass) {
                    advance();
                    return controlLetter % 32;
                }
                if (!('a' <= controlLetter && controlLetter <= 'z' || 'A' <= controlLetter && controlLetter <= 'Z')) {
                    retreat();
                    return escapeCharSyntaxError('\\', ErrorMessages.INVALID_CONTROL_CHAR_ESCAPE);
                }
                advance();
                return Character.toUpperCase(controlLetter) - ('A' - 1);
            case 'u':
                final int unicodeEscape = parseUnicodeEscapeChar();
                return unicodeEscape < 0 ? c : unicodeEscape;
            case 'x':
                final int value = parseHex(2, 2, 0xff, ErrorMessages.INVALID_ESCAPE);
                return value < 0 ? c : value;
            case '-':
                if (!inCharClass) {
                    return escapeCharSyntaxError(c, ErrorMessages.INVALID_ESCAPE);
                }
                return c;
            default:
                if (!flags.isUnicode() && isOctal(c)) {
                    return parseOctal(c - '0');
                }
                if (!SYNTAX_CHARS.get(c)) {
                    return escapeCharSyntaxError(c, ErrorMessages.INVALID_ESCAPE);
                }
                return c;
        }
    }

    private int finishSurrogatePair(char c) {
        assert flags.isUnicode() && Character.isHighSurrogate(c);
        if (!atEnd() && Character.isLowSurrogate(curChar())) {
            final char lead = c;
            final char trail = consumeChar();
            return Character.toCodePoint(lead, trail);
        } else {
            return c;
        }
    }

    private char escapeCharSyntaxError(char c, String msg) throws RegexSyntaxException {
        if (flags.isUnicode()) {
            throw syntaxError(msg);
        }
        return c;
    }

    /**
     * Parses a non-negative decimal integer. The value of the integer is clamped to
     * {@link Integer#MAX_VALUE}. For all {@code i} in {0,1,..,9}, {@code parseInteger(i)} is
     * equivalent to
     * {@code parseDecimal(BigInteger.valueOf(i)).max(BigInteger.valueOf(Integer.MAX_VALUE)}.
     * {@link #parseInteger(int)} should be faster than {@link #parseDecimal(java.math.BigInteger)}
     * because it does not have to go through {@link BigInteger}s.
     */
    private int parseInteger(int firstDigit) {
        int ret = firstDigit;
        // First, we consume all of the decimal digits that make up the integer.
        final int initialIndex = index;
        while (!atEnd() && isDecimal(curChar())) {
            advance();
        }
        final int terminalIndex = index;
        // Then, we parse the integer, stopping once we reach the limit Integer.MAX_VALUE.
        for (int i = initialIndex; i < terminalIndex; i++) {
            int nextDigit = pattern.charAt(i) - '0';
            if (ret > Integer.MAX_VALUE / 10) {
                return Integer.MAX_VALUE;
            }
            ret *= 10;
            if (ret > Integer.MAX_VALUE - nextDigit) {
                return Integer.MAX_VALUE;
            }
            ret += nextDigit;
        }
        return ret;
    }

    private int parseHex(int minDigits, int maxDigits, int maxValue, String errorMsg) throws RegexSyntaxException {
        int ret = 0;
        int initialIndex = index;
        for (int i = 0; i < maxDigits; i++) {
            if (atEnd() || !isHex(curChar())) {
                if (i < minDigits) {
                    if (flags.isUnicode()) {
                        throw syntaxError(errorMsg);
                    } else {
                        index = initialIndex;
                        return -1;
                    }
                } else {
                    break;
                }
            }
            final char c = consumeChar();
            ret *= 16;
            if (c >= 'a') {
                ret += c - ('a' - 10);
            } else if (c >= 'A') {
                ret += c - ('A' - 10);
            } else {
                ret += c - '0';
            }
            if (ret > maxValue) {
                throw syntaxError(errorMsg);
            }
        }
        return ret;
    }

    private static boolean isPredefCharClass(char c) {
        return PREDEFINED_CHAR_CLASSES.get(c);
    }

    private boolean isEscapeCharClass(char c) {
        return isPredefCharClass(c) || (flags.isUnicode() && (c == 'p' || c == 'P'));
    }
}
