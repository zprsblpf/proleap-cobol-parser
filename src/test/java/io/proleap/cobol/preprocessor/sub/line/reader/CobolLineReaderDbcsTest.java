/*
 * Copyright (C) 2017, Ulrich Wolffgang <ulrich.wolffgang@proleap.io>
 * All rights reserved.
 *
 * This software may be modified and distributed under the terms
 * of the MIT license. See the LICENSE file for details.
 */

package io.proleap.cobol.preprocessor.sub.line.reader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import io.proleap.cobol.asg.params.CobolParserParams;
import io.proleap.cobol.asg.params.impl.CobolParserParamsImpl;
import io.proleap.cobol.preprocessor.CobolPreprocessor.CobolSourceFormatEnum;
import io.proleap.cobol.preprocessor.sub.CobolLine;
import io.proleap.cobol.preprocessor.sub.line.reader.impl.CobolLineReaderImpl;

/**
 * TDD 测试：FIXED 格式按显示宽度切列，根治 DBCS/CJK 列错位。
 *
 * 对应设计文档：docs/详细设计/task-3-brief.md
 *
 * 场景：含中文全角字符的 FIXED 行，字符数切分会把行尾变更标记 {@code <A36415>}
 * 错误地算进代码区（cols 8-72）；显示宽度切分应将其推入注释区（cols 73+）。
 */
public class CobolLineReaderDbcsTest {

    // FIXED: 1-6序号 / 7指示符 / 8-72 A+B区 / 73-80 注释区，按显示宽度，中文占2列
    @Test
    public void parsesFixedLineWithDbcsByDisplayWidth() {
        final CobolLineReader reader = new CobolLineReaderImpl();

        // 构造：序号6列 + 空格指示符 + 含中文的代码 + 73列起的变更标记 <A36415>
        String line = "100100     MOVE 中文字段 TO WS-X.                                  <A36415>";

        final CobolParserParams params = new CobolParserParamsImpl();
        params.setFormat(CobolSourceFormatEnum.FIXED);

        CobolLine result = reader.parseLine(line, 1, params);
        assertEquals("100100", result.getSequenceAreaOriginal());      // 1-6 列
        assertFalse(result.getContentArea().contains("<A36415>"));     // 变更标记不得落入代码区
        assertTrue(result.getContentArea().contains("中文字段"));        // 中文完整保留在代码区
    }
}
