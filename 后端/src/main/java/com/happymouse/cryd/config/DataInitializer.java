package com.happymouse.cryd.config;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.happymouse.cryd.model.entity.*;
import com.happymouse.cryd.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据初始化 - 启动时创建默认账号和C语言题库
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final SysUserRepository sysUserRepository;
    private final CourseRepository courseRepository;
    private final ChapterRepository chapterRepository;
    private final QuestionRepository questionRepository;
    private final PasswordEncoder passwordEncoder;
    private final SystemConfigRepository systemConfigRepository;
    private final JdbcTemplate jdbcTemplate;

    public DataInitializer(SysUserRepository sysUserRepository,
                           CourseRepository courseRepository,
                           ChapterRepository chapterRepository,
                           QuestionRepository questionRepository,
                           PasswordEncoder passwordEncoder,
                           SystemConfigRepository systemConfigRepository,
                           JdbcTemplate jdbcTemplate) {
        this.sysUserRepository = sysUserRepository;
        this.courseRepository = courseRepository;
        this.chapterRepository = chapterRepository;
        this.questionRepository = questionRepository;
        this.passwordEncoder = passwordEncoder;
        this.systemConfigRepository = systemConfigRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) {
        initDefaultUsers();
        initCProgrammingBank();
        initSystemConfig();
        syncSequences();
    }

    /**
     * IDENTITY → SEQUENCE 迁移后，修正所有序列起点到当前最大ID之后
     */
    private void syncSequences() {
        try {
            List<String> sequences = jdbcTemplate.queryForList(
                "SELECT sequence_name FROM information_schema.sequences WHERE sequence_name LIKE '%_seq'", String.class);
            for (String seq : sequences) {
                String tbl = seq.replace("_seq", "");
                try {
                    Integer maxId = jdbcTemplate.queryForObject(
                        "SELECT COALESCE(MAX(id), 0) FROM " + tbl, Integer.class);
                    if (maxId != null && maxId > 0) {
                        jdbcTemplate.execute("SELECT setval('" + seq + "', " + maxId + ")");
                        log.info("序列同步: {}({})", seq, maxId);
                    }
                } catch (Exception ignored) {
                    // 序列名和表名不对应，跳过
                }
            }
        } catch (Exception e) {
            log.warn("序列同步失败: {}", e.getMessage());
        }
    }

    /**
     * 初始化系统配置默认值（讯飞星火大模型参数）
     */
    private void initSystemConfig() {
        if (systemConfigRepository.count() > 0) return;

        List<SystemConfig> defaults = List.of(
            createConfig("llm.apiUrl", "https://api.deepseek.com/v1/chat/completions", "DeepSeek API 地址", "llm"),
            createConfig("llm.apiKey", "REDACTED_OLD_KEY", "DeepSeek API Key", "llm"),
            createConfig("llm.model", "deepseek-chat", "默认模型：DeepSeek-V4-Pro", "llm"),
            createConfig("llm.temperature", "0.5", "默认 Temperature", "llm"),
            createConfig("llm.maxTokens", "4096", "默认 Max Tokens", "llm"),
            createConfig("llm.topP", "0.9", "默认 Top-P", "llm"),
            createConfig("llm.timeout", "60", "请求超时（秒）", "llm")
        );

        systemConfigRepository.saveAll(defaults);
        System.out.println("⚙️  系统配置已初始化: DeepSeek-V4-Pro 为默认模型");
    }

    private SystemConfig createConfig(String key, String value, String desc, String category) {
        SystemConfig config = new SystemConfig();
        config.setConfigKey(key);
        config.setConfigValue(value);
        config.setDescription(desc);
        config.setCategory(category);
        return config;
    }

    private void initDefaultUsers() {
        List<DefaultUser> defaults = List.of(
            new DefaultUser("admin1", "123456", "系统管理员", "admin", "", "信息中心", "13800000001", null),
            new DefaultUser("student1", "123456", "张同学", "student", "计科2301", "", "13800000002", "2023010001"),
            new DefaultUser("teacher1", "123456", "李老师", "teacher", "", "计算机系", "13800000003", null)
        );

        for (DefaultUser d : defaults) {
            if (sysUserRepository.findByUsername(d.username).isEmpty()) {
                SysUser user = new SysUser();
                user.setUsername(d.username);
                user.setPassword(passwordEncoder.encode(d.password));
                user.setNickname(d.nickname);
                user.setRole(d.role);
                user.setClassName(d.className);
                user.setDepartment(d.department);
                user.setPhone(d.phone);
                user.setStudentId(d.studentId);
                user.setStatus("active");
                sysUserRepository.save(user);
            }
        }
    }

    /**
     * 初始化谭浩强《C语言程序设计》题库
     */
    private void initCProgrammingBank() {
        SysUser teacher = sysUserRepository.findByUsername("teacher1").orElse(null);
        Long teacherId = teacher != null ? teacher.getId() : 1L;

        try {
            ClassPathResource resource = new ClassPathResource("question-bank/c-programming.json");
            String jsonStr;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line).append("\n");
                jsonStr = sb.toString();
            }

            JSONObject root = JSON.parseObject(jsonStr);
            JSONArray chapters = root.getJSONArray("chapters");
            if (chapters.isEmpty()) return;

            // 获取或创建课程
            List<Course> existingCourses = courseRepository.findAllByName("C语言程序设计");
            Course course = existingCourses.isEmpty() ? null : existingCourses.get(0);
            if (course == null) {
                course = new Course();
                course.setName("C语言程序设计");
                course.setCode("C001");
                course.setTeacherId(teacherId);
                course.setSemester("2025-2026-2");
                course.setClassName("计科2301"); // 设置授课班级
                course.setDescription("谭浩强《C语言程序设计》课后习题题库，每小节含4道选择题和1道代码编写题");
                course = courseRepository.save(course);
                System.out.println("📚 创建课程：C语言程序设计（班级：计科2301）");
            } else if (course.getClassName() == null || course.getClassName().isEmpty()) {
                // 如果课程已存在但没有设置班级，补充设置
                course.setClassName("计科2301");
                course = courseRepository.save(course);
                System.out.println("📚 更新课程班级：计科2301");
            }

            // 创建或更新章节（检查是否已存在，避免重复）
            int createdCount = 0;
            int updatedCount = 0;
            for (int i = 0; i < chapters.size(); i++) {
                JSONObject ch = chapters.getJSONObject(i);
                String chapterNameRaw = ch.getString("chapterName");
                if (chapterNameRaw == null) chapterNameRaw = ch.getString("name"); // 兼容两种格式
                final String chapterName = chapterNameRaw;
                JSONArray sections = ch.getJSONArray("sections");
                JSONArray questionsRaw = ch.getJSONArray("questions"); // 有些格式直接在章节级别
                if (questionsRaw == null && sections != null) {
                    // 从 sections 里汇总所有 questions
                    questionsRaw = new JSONArray();
                    for (int s = 0; s < sections.size(); s++) {
                        JSONArray sq = sections.getJSONObject(s).getJSONArray("questions");
                        if (sq != null) {
                            for (int q = 0; q < sq.size(); q++) {
                                questionsRaw.add(sq.get(q));
                            }
                        }
                    }
                }
                final JSONArray questions = questionsRaw != null ? questionsRaw : new JSONArray();

                // 查找是否已有同名章节
                List<Chapter> existing = chapterRepository.findByStatusOrderByOrderNum("published");
                Chapter chapter = existing.stream()
                        .filter(c -> c.getName().equals(chapterName))
                        .findFirst().orElse(null);

                if (chapter == null) {
                    chapter = new Chapter();
                    chapter.setCourseId(course.getId());
                    chapter.setTeacherId(teacherId);
                    chapter.setName(chapterName);
                    chapter.setDescription(ch.getString("description") != null ? ch.getString("description") : chapterName);
                    Integer orderNum = ch.getInteger("orderNum");
                    if (orderNum == null) orderNum = ch.getInteger("chapterId"); // 兼容两种格式
                    chapter.setOrderNum(orderNum != null ? orderNum : i + 1);
                    chapter.setStatus("published");
                    chapter.setPublishedAt(LocalDateTime.now());
                    chapter.setQuestions(questions.toJSONString());
                    chapterRepository.save(chapter);
                    createdCount++;
                } else if (chapter.getQuestions() == null || getQuestionCount(chapter.getQuestions()) < questions.size()) {
                    // 更新已有章节的题目（JSON文件有更多题目时）
                    chapter.setQuestions(questions.toJSONString());
                    chapterRepository.save(chapter);
                    updatedCount++;
                }
            }

            System.out.println("✅ C语言题库初始化完成: 新增" + createdCount + "章, 更新" + updatedCount + "章, 共" + chapters.size() + "章");

            // 逐题导入题库表 (question)
            int importedQ = 0;
            for (int i = 0; i < chapters.size(); i++) {
                JSONObject ch = chapters.getJSONObject(i);
                String chapterNameQ = ch.getString("chapterName") != null ? ch.getString("chapterName") : ch.getString("name");
                Integer chapterOrderRaw = ch.getInteger("orderNum");
                if (chapterOrderRaw == null) chapterOrderRaw = ch.getInteger("chapterId");
                int chapterOrder = chapterOrderRaw != null ? chapterOrderRaw : i + 1;

                // 汇总sections里的questions
                JSONArray sectionsQ = ch.getJSONArray("sections");
                JSONArray questionsQ = ch.getJSONArray("questions");
                if (questionsQ == null && sectionsQ != null) {
                    questionsQ = new JSONArray();
                    for (int s = 0; s < sectionsQ.size(); s++) {
                        JSONArray sq = sectionsQ.getJSONObject(s).getJSONArray("questions");
                        if (sq != null) {
                            for (int qi = 0; qi < sq.size(); qi++) {
                                questionsQ.add(sq.get(qi));
                            }
                        }
                    }
                }
                if (questionsQ == null) continue;

                final String chName = chapterNameQ;
                for (int j = 0; j < questionsQ.size(); j++) {
                    JSONObject q = questionsQ.getJSONObject(j);
                    String type = q.getString("type");
                    String content = q.getString("question") != null ? q.getString("question") : q.getString("content");

                    // 检查是否已存在（按内容去重）
                    boolean exists = questionRepository.findAll().stream()
                            .anyMatch(existing -> content.equals(existing.getContent())
                                    && chName.equals(existing.getChapterName()));
                    if (exists) continue;

                    Question question = new Question();
                    question.setType(type);
                    question.setContent(content);
                    if ("choice".equals(type) && q.containsKey("options")) {
                        question.setOptions(q.getJSONArray("options").toJSONString());
                    }
                    question.setAnswer(q.getString("answer"));
                    question.setKnowledgePoint(q.getString("knowledgePoint"));
                    question.setAnalysis(q.getString("explanation") != null ? q.getString("explanation") : q.getString("analysis"));
                    question.setDifficulty(q.getString("difficulty") != null ? q.getString("difficulty") : "medium");
                    question.setChapterName(chName);
                    question.setChapterOrder(chapterOrder);
                    question.setCourseId(course.getId());
                    question.setSource("imported");
                    questionRepository.save(question);
                    importedQ++;
                }
            }
            System.out.println("📋 题库表导入完成: " + importedQ + " 道题目");

            System.out.println("   题库JSON路径: question-bank/c-programming.json");
            System.out.println("   如需扩展题目, 启动后端后调用: POST /api/teacher/expand-question-bank");
        } catch (Exception e) {
            System.err.println("⚠️ C语言题库初始化失败：" + e.getMessage());
            e.printStackTrace();
        }
    }

    private int getQuestionCount(String questionsJson) {
        try { return JSON.parseArray(questionsJson).size(); } catch (Exception e) { return 0; }
    }

    private static class DefaultUser {
        String username, password, nickname, role, className, department, phone, studentId;
        DefaultUser(String u, String p, String n, String r, String c, String d, String ph, String sid) {
            username = u; password = p; nickname = n; role = r; className = c; department = d; phone = ph; studentId = sid;
        }
    }
}