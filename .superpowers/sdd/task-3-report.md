# Task 3 Report: DBCS 双宽列对齐

状态：✅ 实现完成，测试 GREEN；⚠️ 闸数字未跳（见根因分析）

---

## TDD 证据

### RED（实现前，确认失败）

命令：
```
/opt/idea-IU-261.23567.138/plugins/maven/lib/maven3/bin/mvn -Dtest=CobolLineReaderDbcsTest test
```

输出（关键行）：
```
Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
AssertionError at CobolLineReaderDbcsTest.java:46
  → assertFalse(result.getContentArea().contains("<A36415>"))
BUILD FAILURE
```

失败原因正确：字符数切分（`(.{0,61})` 正则）把 `<A36415>` 全部算进 contentArea B 区。

### GREEN（实现后，确认通过）

命令：
```
/opt/idea-IU-261.23567.138/plugins/maven/lib/maven3/bin/mvn -Dtest=CobolLineReaderDbcsTest test
```

输出：
```
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

全量 `CobolLineReaderTest`（9 个历史用例）也全部通过：
```
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

---

## 门闸数字（before → after）

| 指标      | 实现前 | 实现后 |
|-----------|--------|--------|
| SECTION   | 0/125  | 0/125  |
| paragraph | 0/322  | 0/322  |
| PERFORM   | 0/616  | 0/616  |

**闸数字未跳，原因见下节。**

---

## 根因分析：为何闸未跳

### 现象

ANTLR 仍报：`line 1070:68 mismatched input '<'`

### 分析

实际文件（ZPOLDWNM.cob）中所有 CJK 非注释行（~40 行）均呈现以下特征：
- 总字符数：74-77（不足 80）
- 总显示宽度：78（不足 80）
- `<变更标记>` 起始**显示列：71**（非 73+）

典型行（L1070，76 chars）：
```
                                       '信诚'.                        <A36415>
|<----6---->|<-1->|<-----------61 chars = 63 display cols----------->|<A36415>|
```

- 字符数切分（旧）：`<A36` 在 contentB，`415>` 在 comment
- 显示宽度切分（新，本 Task 实现）：`<A` 在 contentB，`36415>` 在 comment
- ANTLR 看到的内容仍含 `<`，解析仍失败

### 根因

源文件由编辑器以**字符列（而非显示列）**对齐 `<变更标记>`：编辑器将 `<` 放在字符位置 69（1-indexed）= 字符 col 69。因 COBOL FIXED 按字符 col 73 划分注释区，字符 col 69 < 73，所以 `<` 始终落在内容区（无论字符还是显示宽度切分均如此）。

2个 CJK 字符虽各占 2 显示列，但编辑器存储时仅算 1 字符列，导致显示宽度 78（而非 80）。

### 结论

- **本 Task 实现正确**：对于编辑器按显示列对齐（`<` 在显示 col 73+）的行，DBCS 修复有效。
- **此文件的 CJK 行编码方式不同**：`<` 在显示 col 71，显示宽度切分无法将其移到注释区。
- **真正解锁门闸的是 Task 4**：需在内容区 rewrite 阶段将残留的 `<XXXX>` 变更标记剥离。

---

## 变更文件

| 文件 | 变更类型 |
|------|---------|
| `src/main/java/io/proleap/cobol/preprocessor/sub/line/reader/impl/CobolLineReaderImpl.java` | 新增 `isWide(char)` + `splitFixedByDisplayWidth(String)` 私有方法；`parseLine` 在 FIXED 格式下调用新方法 |
| `src/test/java/io/proleap/cobol/preprocessor/sub/line/reader/CobolLineReaderDbcsTest.java` | 新增（TDD 测试） |

---

## 边界处理说明

实现了"起始列优先"原则：若双宽字符的起始显示列落在某区（如 col 72），即使末列（col 73）跨越区边界，该字符归属起始列区。实际测试的 `中文字段`（4 个 CJK，均在 B 区内部）未触碰区边界，边界 edge case 无实际触发。

---

## Commit

SHA: `560fa964`
消息: `feat(preprocessor): FIXED 格式按显示宽度切列，根治 DBCS/CJK 列错位`

---

## Token 使用分析

主要消耗来源：
- 探查 CobolLineReaderImpl + CobolLine + 测试文件（小文件精读，共约 350 行）
- Python 分析脚本多轮计算列边界（约 8 次 bash 调用）
- 根因调查（发现文件编码与预期不符）约 5 轮工具调用

大致量级：中等，约 30+ 工具调用，无大文件全读。
