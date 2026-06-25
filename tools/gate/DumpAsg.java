/*
 * DumpAsg.java —— ProLeap ASG 导出（P0 验证闸，迁自 spike_proleap/DumpAsg.java）
 *
 * 用途：
 *   用 ProLeap COBOL parser 解析一个定宽 COBOL 源，把后续翻译关心的 ASG 关键结构
 *   导成 JSON，供 coverage_report.sh 度量 SECTION/paragraph/PERFORM 覆盖率。
 *
 * 对应设计文档：docs/详细设计/task-1-验证闸脚手架.md（§3 DumpAsg 入口）
 *
 * 设计思路：
 *   - 全程参数化（源路径 / 程序名 / 源格式 / 输出 json / 拷贝簿目录），无业务硬编码；
 *   - 只读取、只导出，不改任何主线产物；
 *   - 有序表用 ASGElement.getCtx().getStart().getLine() 做源序排序；
 *   - PERFORM 收集对所有 Scope 递归，按 ctx 身份去重，避免重复计数；
 *   - Task 2 起可通过 CobolParserParams 开关（ignoreMissingCopyBooks 等）扩展。
 *
 * 用法（经 run_dump.sh 调用）：
 *   java -cp "<deps>:proleap.jar:." DumpAsg <cob文件> <程序名> <FIXED|VARIABLE|TANDEM> <输出json> [拷贝簿目录(冒号分隔)]
 */

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.antlr.v4.runtime.ParserRuleContext;

import io.proleap.cobol.asg.metamodel.ASGElement;
import io.proleap.cobol.asg.metamodel.CompilationUnit;
import io.proleap.cobol.asg.metamodel.Program;
import io.proleap.cobol.asg.metamodel.ProgramUnit;
import io.proleap.cobol.asg.metamodel.Scope;
import io.proleap.cobol.asg.metamodel.call.Call;
import io.proleap.cobol.asg.metamodel.data.DataDivision;
import io.proleap.cobol.asg.metamodel.data.workingstorage.WorkingStorageSection;
import io.proleap.cobol.asg.metamodel.procedure.Paragraph;
import io.proleap.cobol.asg.metamodel.procedure.ProcedureDivision;
import io.proleap.cobol.asg.metamodel.procedure.Section;
import io.proleap.cobol.asg.metamodel.procedure.Statement;
import io.proleap.cobol.asg.metamodel.procedure.perform.PerformProcedureStatement;
import io.proleap.cobol.asg.metamodel.procedure.perform.PerformStatement;
import io.proleap.cobol.asg.params.CobolParserParams;
import io.proleap.cobol.asg.params.impl.CobolParserParamsImpl;
import io.proleap.cobol.asg.runner.impl.CobolParserRunnerImpl;
import io.proleap.cobol.preprocessor.CobolPreprocessor.CobolSourceFormatEnum;
import java.util.Arrays;

public class DumpAsg {

    /** 取任一 ASG 元素的源起始行号（无 ctx 时返回 -1），用于源序排序。 */
    static int lineOf(Object element) {
        if (!(element instanceof ASGElement)) return -1;
        ParserRuleContext ctx = ((ASGElement) element).getCtx();
        return (ctx != null && ctx.getStart() != null) ? ctx.getStart().getLine() : -1;
    }

    /** 递归收集某 Scope 内的全部 PERFORM 语句，按 ctx 身份去重写入 acc。 */
    static void collectPerforms(Scope scope, List<PerformStatement> acc, Map<Object, Boolean> seen) {
        if (scope == null) return;
        for (Statement st : scope.getStatements()) {
            if (st instanceof PerformStatement) {
                Object ctx = (st instanceof ASGElement) ? ((ASGElement) st).getCtx() : st;
                if (seen.put(ctx, Boolean.TRUE) == null) acc.add((PerformStatement) st);
            }
            if (st instanceof Scope) collectPerforms((Scope) st, acc, seen); // 内联 PERFORM 体等
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("用法: DumpAsg <cob文件> <程序名> <FIXED|VARIABLE|TANDEM> <输出json> [拷贝簿目录(冒号分隔)]");
            System.exit(2);
        }
        File cob = new File(args[0]);
        String programName = args[1];
        CobolSourceFormatEnum fmt = CobolSourceFormatEnum.valueOf(args[2]);
        File out = new File(args[3]);
        String copyDirsArg = args.length >= 5 ? args[4] : "";

        // 拷贝簿参数：设置目录和扩展名（Task 2 起可加 ignoreMissingCopyBooks 等开关）
        CobolParserParams params = new CobolParserParamsImpl();
        params.setFormat(fmt);
        params.setIgnoreSyntaxErrors(true); // 容错：脏源语法错误不中断，尽量产出 ASG
        params.setIgnoreMissingCopyBooks(true); // Task 2：缺簿跳过不中断，插空串继续解析
        params.setCopyBookExtensions(Arrays.asList("cob"));
        List<File> copyDirs = new ArrayList<>();
        if (!copyDirsArg.isEmpty())
            for (String p : copyDirsArg.split(":")) if (!p.isEmpty()) copyDirs.add(new File(p));
        if (!copyDirs.isEmpty()) params.setCopyBookDirectories(copyDirs);

        long t0 = System.currentTimeMillis();
        Program program = new CobolParserRunnerImpl().analyzeFile(cob, params);
        long parseMs = System.currentTimeMillis() - t0;

        CompilationUnit cu = program.getCompilationUnit(programName);
        if (cu == null && program.getCompilationUnits().size() == 1)
            cu = program.getCompilationUnits().get(0); // 文件名派生名 != 程序名时兜底
        if (cu == null) {
            List<String> names = new ArrayList<>();
            for (CompilationUnit c : program.getCompilationUnits()) names.add(c.getName());
            throw new IllegalStateException("找不到编译单元 " + programName
                + "；现有编译单元=" + names + "（共 " + names.size() + " 个）");
        }
        ProgramUnit pu = cu.getProgramUnit();
        ProcedureDivision pd = pu.getProcedureDivision();
        DataDivision dd = pu.getDataDivision();

        // —— SECTION 有序表（pd 为 null 表示解析未产出 PROCEDURE DIVISION，退化为空列表）——
        List<Section> sections = new ArrayList<>(pd != null ? pd.getSections() : java.util.Collections.emptyList());
        sections.sort((a, b) -> Integer.compare(lineOf(a), lineOf(b)));

        // —— paragraph 有序表（标注所属 section；段外 paragraph 标 null） ——
        Map<Paragraph, String> paraSection = new LinkedHashMap<>();
        for (Section s : sections)
            for (Paragraph p : s.getParagraphs()) paraSection.put(p, s.getName());
        List<Paragraph> paragraphs = new ArrayList<>(pd != null ? pd.getParagraphs() : java.util.Collections.emptyList());
        paragraphs.sort((a, b) -> Integer.compare(lineOf(a), lineOf(b)));

        // —— PERFORM 收集（含 THRU 端点） ——
        List<PerformStatement> performs = new ArrayList<>();
        Map<Object, Boolean> seen = new java.util.IdentityHashMap<>();
        if (pd != null) collectPerforms(pd, performs, seen);
        for (Section s : sections) collectPerforms(s, performs, seen);
        for (Paragraph p : paragraphs) collectPerforms(p, performs, seen);
        performs.sort((a, b) -> Integer.compare(lineOf(a), lineOf(b)));

        // —— WORKING-STORAGE 变量数 ——
        int wsAll = -1, wsRoot = -1;
        WorkingStorageSection ws = (dd == null) ? null : dd.getWorkingStorageSection();
        if (ws != null) {
            wsAll = ws.getDataDescriptionEntries().size();
            wsRoot = ws.getRootDataDescriptionEntries().size();
        }

        // —— 手写 JSON 输出 ——
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"program\": ").append(q(programName)).append(",\n");
        sb.append("  \"parse_ms\": ").append(parseMs).append(",\n");
        sb.append("  \"ws_var_count\": {\"all\": ").append(wsAll).append(", \"root\": ").append(wsRoot).append("},\n");

        sb.append("  \"sections\": [\n");
        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            sb.append("    {\"name\": ").append(q(s.getName())).append(", \"line\": ").append(lineOf(s)).append("}");
            sb.append(i < sections.size() - 1 ? ",\n" : "\n");
        }
        sb.append("  ],\n");

        sb.append("  \"paragraphs\": [\n");
        for (int i = 0; i < paragraphs.size(); i++) {
            Paragraph p = paragraphs.get(i);
            String sec = paraSection.get(p);
            sb.append("    {\"name\": ").append(q(p.getName()))
              .append(", \"line\": ").append(lineOf(p))
              .append(", \"section\": ").append(sec == null ? "null" : q(sec)).append("}");
            sb.append(i < paragraphs.size() - 1 ? ",\n" : "\n");
        }
        sb.append("  ],\n");

        sb.append("  \"performs\": [\n");
        for (int i = 0; i < performs.size(); i++) {
            PerformStatement ps = performs.get(i);
            String type = String.valueOf(ps.getPerformStatementType()); // INLINE / PROCEDURE
            List<String> targets = new ArrayList<>();
            String loop = null;
            PerformProcedureStatement pp = ps.getPerformProcedureStatement();
            if (pp != null) {
                for (Call c : pp.getCalls()) targets.add(c.getName()); // THRU: [from, thru]
                loop = String.valueOf(pp.getPerformType()); // TIMES/UNTIL/VARYING/null
            }
            sb.append("    {\"line\": ").append(lineOf(ps))
              .append(", \"type\": ").append(q(type))
              .append(", \"loop\": ").append("UNTIL".equals(loop) || "TIMES".equals(loop) || "VARYING".equals(loop) ? q(loop) : "null")
              .append(", \"targets\": ").append(arr(targets)).append("}");
            sb.append(i < performs.size() - 1 ? ",\n" : "\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");

        try (FileWriter w = new FileWriter(out)) {
            w.write(sb.toString());
        }
        System.out.println("解析耗时 " + parseMs + "ms; sections=" + sections.size()
            + " paragraphs=" + paragraphs.size() + " performs=" + performs.size()
            + " ws(all/root)=" + wsAll + "/" + wsRoot + " -> " + out.getPath());
    }

    /** JSON 字符串转义。 */
    static String q(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default: b.append(c);
            }
        }
        return b.append("\"").toString();
    }

    /** 字符串列表 → JSON 数组。 */
    static String arr(List<String> xs) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < xs.size(); i++) {
            b.append(q(xs.get(i)));
            if (i < xs.size() - 1) b.append(", ");
        }
        return b.append("]").toString();
    }
}
