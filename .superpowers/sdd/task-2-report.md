# Task 2 Report：ignoreMissingCopyBooks 缺簿容忍

状态：✅ 实现完成，提交已打入

---

## 变更摘要

### 文件改动

1. **`src/main/java/io/proleap/cobol/asg/params/CobolParserParams.java`**
   - 新增接口方法 `boolean getIgnoreMissingCopyBooks()` 和 `void setIgnoreMissingCopyBooks(boolean)`
   - 镜像既有 `getIgnoreSyntaxErrors` / `setIgnoreSyntaxErrors` 结构

2. **`src/main/java/io/proleap/cobol/asg/params/impl/CobolParserParamsImpl.java`**
   - 新增字段 `protected boolean ignoreMissingCopyBooks = false;`（默认 false，保持上游兼容）
   - 实现 getter/setter

3. **`src/main/java/io/proleap/cobol/preprocessor/sub/document/impl/CobolDocumentParserListenerImpl.java`**
   - 新增 `import java.util.HashSet` / `import java.util.Set`
   - 新增字段 `private final Set<String> missingCopyBooks = new HashSet<String>();`
   - 在 `getCopyBookContent()` 的 `copyBook == null` 分支加入开关判断：
     - `ignoreMissingCopyBooks=true` → `LOG.warn` + 记录缺簿名 + `return ""`（插空串，不抛）
     - `ignoreMissingCopyBooks=false` → 保留原 `throw`（上游行为不变）
   - 新增公开 getter `public Set<String> getMissingCopyBooks()`

4. **`tools/gate/DumpAsg.java`**
   - 在构造 params 处加入 `params.setIgnoreMissingCopyBooks(true);`
   - 将 `pd.getSections()` / `pd.getParagraphs()` 改为 null 守卫（`pd != null ? ... : emptyList()`），
     防止 Task 3 尚未修复时 PROCEDURE DIVISION 为 null 导致 NPE crash

---

## 构建结果

```
mvn package -DskipTests -q  # 零错误，零警告
```

---

## 验证闸结果

### Task 1 基线（Before Task 2）

```
=== P0 验证闸覆盖率报告 ===
SECTION   0/125 (0.0%)
paragraph 0/322 (0.0%)
PERFORM   0/616 (0.0%)
===========================
```

崩溃原因：`CobolPreprocessorException: Could not find copy book ITEMKEY ...`

### Task 2 之后（After）

```
=== P0 验证闸覆盖率报告 ===
SECTION   0/125 (0.0%)
paragraph 0/322 (0.0%)
PERFORM   0/616 (0.0%)
===========================
```

三项计数仍为 0，但**崩溃原因已改变**：
- 缺簿异常不再抛出（128+ 个缺簿全部被 `LOG.warn` 跳过）
- 解析成功走完 DATA DIVISION，WORKING-STORAGE 变量已解析（all=124, root=100）
- 计数仍为 0 的新根因：line 1070 出现 `mismatched input '<'`（DBCS 双宽字符 `信诚` 使
  `<A36415>` 变更标记位移到字符第 69 列，落入 Area B 内容区而非 cols 73-80 注释区）
- 此为 Task 3 待修问题（DBCS 双宽列对齐），不是 Task 2 范围

---

## 关键观察

- `ignoreSyntaxErrors=true` 已经让语法错误不抛异常（仅打到 stderr）
- ANTLR 错误恢复后 `ProcedureDivision` 仍为 null（ANTLR 无法从 `<` 处重建 PROCEDURE DIVISION）
- Task 2 预期的"counts jump from 0"**需 Task 3 修复 DBCS 列后才能实现**
- 代码实现本身正确：接口/实现/listener 三处严格镜像 `IgnoreSyntaxErrors` 模式

---

## 关注点 / 后续

- Task 3 应修复 DBCS 双宽字符导致的列偏移，届时 PROCEDURE DIVISION 将被正确解析，三项计数预计大幅上升
- `DumpAsg.java` 的 null pd 守卫保证了 Task 3 完成前闸门不 crash，计数为 0 而非异常退出

---

## Token 使用分析

本任务主要消耗来源：
- 源码读取（CobolParserParams*.java、ListenerImpl.java、DumpAsg.java、run_dump.sh、coverage_report.sh）：约 6 次 Read 调用
- 构建 + 验证闸各 2 轮（mvn package + gate run）
- 诊断 DBCS `<` 问题的额外探查（grep × 4，Read × 2）
- 整体量级：中等，约 15-20 轮工具调用
