/*
 * Copyright (C) 2017, Ulrich Wolffgang <ulrich.wolffgang@proleap.io>
 * All rights reserved.
 *
 * This software may be modified and distributed under the terms
 * of the MIT license. See the LICENSE file for details.
 */

package io.proleap.cobol.preprocessor.sub.line.reader.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.proleap.cobol.asg.params.CobolParserParams;
import io.proleap.cobol.preprocessor.CobolPreprocessor;
import io.proleap.cobol.preprocessor.CobolPreprocessor.CobolSourceFormatEnum;
import io.proleap.cobol.preprocessor.exception.CobolPreprocessorException;
import io.proleap.cobol.preprocessor.sub.CobolLine;
import io.proleap.cobol.preprocessor.sub.CobolLineTypeEnum;
import io.proleap.cobol.preprocessor.sub.line.reader.CobolLineReader;

public class CobolLineReaderImpl implements CobolLineReader {

	/**
	 * 判断字符是否为全角（显示宽度 = 2 列）。
	 *
	 * 覆盖范围：CJK 统一表意文字、扩展 A、假名、谚文、全角 ASCII/符号、CJK 符号与标点。
	 * 设计思路：用 Unicode 码点范围判断，避免依赖不同 JDK 版本行为差异的
	 * {@link Character#getType}。
	 */
	private static boolean isWide(final char c) {
		final int cp = c;
		// CJK Unified Ideographs (含常用汉字)
		if (cp >= 0x4E00 && cp <= 0x9FFF) return true;
		// CJK Extension A
		if (cp >= 0x3400 && cp <= 0x4DBF) return true;
		// Hiragana & Katakana
		if (cp >= 0x3040 && cp <= 0x30FF) return true;
		// Hangul Syllables
		if (cp >= 0xAC00 && cp <= 0xD7AF) return true;
		// Fullwidth ASCII & Halfwidth/Fullwidth Forms (全角英数)
		if (cp >= 0xFF01 && cp <= 0xFF60) return true;
		// CJK Symbols and Punctuation（含全角空格 U+3000）
		if (cp >= 0x3000 && cp <= 0x303F) return true;
		return false;
	}

	/**
	 * 按**显示宽度**将 FIXED 格式行切分为 5 个区域字符串，返回长度为 5 的数组：
	 * {@code [sequenceArea, indicatorArea, contentAreaA, contentAreaB, commentArea]}。
	 *
	 * FIXED 列定义（显示列，1-based）：
	 * <ul>
	 *   <li>1-6：序号区（Sequence Area）</li>
	 *   <li>7：指示符区（Indicator Area）</li>
	 *   <li>8-11：A 区（Content Area A，4 显示列）</li>
	 *   <li>12-72：B 区（Content Area B，61 显示列）</li>
	 *   <li>73+：注释区（Comment Area）</li>
	 * </ul>
	 *
	 * 边界处理：若一个双宽字符的起始显示列落在某区内（即便其末列跨越区边界），
	 * 该字符归属起始列所在的区（按起始列优先原则）。这与 IBM 主机 EBCDIC 实践一致：
	 * 解析器按列边界截断时，双宽字符的第二个显示列位置本不含独立字符。
	 *
	 * @param line 原始 COBOL 行文本（不含行尾换行符）
	 * @return 长度为 5 的字符串数组
	 */
	private String[] splitFixedByDisplayWidth(final String line) {
		final StringBuilder seq = new StringBuilder();      // cols 1-6
		final StringBuilder ind = new StringBuilder();      // col 7
		final StringBuilder areaA = new StringBuilder();    // cols 8-11
		final StringBuilder areaB = new StringBuilder();    // cols 12-72
		final StringBuilder comment = new StringBuilder();  // cols 73+

		int displayCol = 1; // 当前字符的起始显示列（1-based）

		for (int i = 0; i < line.length(); i++) {
			final char c = line.charAt(i);
			final int width = isWide(c) ? 2 : 1;

			// 按起始列归区（边界处双宽字符归起始列所在区）
			if (displayCol <= 6) {
				seq.append(c);
			} else if (displayCol == 7) {
				ind.append(c);
			} else if (displayCol <= 11) {
				areaA.append(c);
			} else if (displayCol <= 72) {
				areaB.append(c);
			} else {
				comment.append(c);
			}

			displayCol += width;
		}

		return new String[] {
			seq.toString(),
			ind.length() > 0 ? ind.toString() : " ",
			areaA.toString(),
			areaB.toString(),
			comment.toString()
		};
	}

	protected CobolLineTypeEnum determineType(final String indicatorArea) {
		final CobolLineTypeEnum result;

		switch (indicatorArea) {
		case CobolPreprocessor.CHAR_D:
		case CobolPreprocessor.CHAR_D_:
			result = CobolLineTypeEnum.DEBUG;
			break;
		case CobolPreprocessor.CHAR_MINUS:
			result = CobolLineTypeEnum.CONTINUATION;
			break;
		case CobolPreprocessor.CHAR_ASTERISK:
		case CobolPreprocessor.CHAR_SLASH:
			result = CobolLineTypeEnum.COMMENT;
			break;
		case CobolPreprocessor.CHAR_DOLLAR_SIGN:
			result = CobolLineTypeEnum.COMPILER_DIRECTIVE;
			break;
		case CobolPreprocessor.WS:
		default:
			result = CobolLineTypeEnum.NORMAL;
			break;
		}

		return result;
	}

	@Override
	public CobolLine parseLine(final String line, final int lineNumber, final CobolParserParams params) {
		final CobolSourceFormatEnum format = params.getFormat();
		final CobolLine result;

		if (format == CobolSourceFormatEnum.FIXED) {
			// FIXED 格式：按显示宽度切列，正确处理 CJK 全角字符（每字符占 2 显示列）。
			// TANDEM/VARIABLE 保持原 matcher 逻辑不变（YAGNI）。
			final String[] areas = splitFixedByDisplayWidth(line);
			final String sequenceArea  = areas[0];
			final String indicatorArea = areas[1];
			final String contentAreaA  = areas[2];
			final String contentAreaB  = areas[3];
			final String commentArea   = areas[4];

			final CobolLineTypeEnum type = determineType(indicatorArea);

			result = CobolLine.newCobolLine(sequenceArea, indicatorArea, contentAreaA, contentAreaB, commentArea,
					format, params.getDialect(), lineNumber, type);
		} else {
			final Pattern pattern = format.getPattern();
			final Matcher matcher = pattern.matcher(line);

			if (!matcher.matches()) {
				final String formatDescription;

				switch (format) {
				case TANDEM:
					formatDescription = "Column 1 indicator area, columns 2 and all following for areas A and B";
					break;
				case VARIABLE:
					formatDescription = "Columns 1-6 sequence number, column 7 indicator area, columns 8 and all following for areas A and B";
					break;
				default:
					formatDescription = "";
					break;
				}

				final String message = "Is " + params.getFormat() + " the correct line format (" + formatDescription
						+ ")? Could not parse line " + (lineNumber + 1) + ": " + line;

				throw new CobolPreprocessorException(message);
			} else {
				final String sequenceAreaGroup = matcher.group(1);
				final String indicatorAreaGroup = matcher.group(2);
				final String contentAreaAGroup = matcher.group(3);
				final String contentAreaBGroup = matcher.group(4);
				final String commentAreaGroup = matcher.group(5);

				final String sequenceArea = sequenceAreaGroup != null ? sequenceAreaGroup : "";
				final String indicatorArea = indicatorAreaGroup != null ? indicatorAreaGroup : " ";
				final String contentAreaA = contentAreaAGroup != null ? contentAreaAGroup : "";
				final String contentAreaB = contentAreaBGroup != null ? contentAreaBGroup : "";
				final String commentArea = commentAreaGroup != null ? commentAreaGroup : "";

				final CobolLineTypeEnum type = determineType(indicatorArea);

				result = CobolLine.newCobolLine(sequenceArea, indicatorArea, contentAreaA, contentAreaB, commentArea,
						format, params.getDialect(), lineNumber, type);
			}
		}

		return result;
	}

	@Override
	public List<CobolLine> processLines(final String lines, final CobolParserParams params) {
		final Scanner scanner = new Scanner(lines);
		final List<CobolLine> result = new ArrayList<CobolLine>();

		String currentLine = null;
		CobolLine lastCobolLine = null;
		int lineNumber = 0;

		while (scanner.hasNextLine()) {
			currentLine = scanner.nextLine();

			final CobolLine currentCobolLine = parseLine(currentLine, lineNumber, params);
			currentCobolLine.setPredecessor(lastCobolLine);
			result.add(currentCobolLine);

			lineNumber++;
			lastCobolLine = currentCobolLine;
		}

		scanner.close();
		return result;
	}
}
