package top.hcode.hoj.remoteJudge.task.Impl;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.http.Method;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpException;
import org.apache.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import top.hcode.hoj.remoteJudge.task.RemoteJudgeStrategy;
import top.hcode.hoj.util.Constants;

import java.net.HttpCookie;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j(topic = "hoj")
public class CodeForcesJudge implements RemoteJudgeStrategy {
    public static final String IMAGE_HOST = "https://codeforces.ml/";
    public static final String HOST = "https://codeforces.com/";
    public static final String LOGIN_URL = "enter";
    public static final String SUBMIT_URL = "problemset/submit";
    public static final String SUBMISSION_RESULT_URL = "api/user.status?handle=%s&from=1&count=%s";
    public static final String CE_INFO_URL = "data/submitSource";
    protected List<HttpCookie> cookies = new LinkedList<>();

    private static final Map<String, Constants.Judge> statusMap = new HashMap<String, Constants.Judge>() {{
        put("FAILED", Constants.Judge.STATUS_SUBMITTED_FAILED);
        put("OK", Constants.Judge.STATUS_ACCEPTED);
        put("PARTIAL", Constants.Judge.STATUS_PARTIAL_ACCEPTED);
        put("COMPILATION_ERROR", Constants.Judge.STATUS_COMPILE_ERROR);
        put("RUNTIME_ERROR", Constants.Judge.STATUS_RUNTIME_ERROR);
        put("WRONG_ANSWER", Constants.Judge.STATUS_WRONG_ANSWER);
        put("PRESENTATION_ERROR", Constants.Judge.STATUS_PRESENTATION_ERROR);
        put("TIME_LIMIT_EXCEEDED", Constants.Judge.STATUS_TIME_LIMIT_EXCEEDED);
        put("MEMORY_LIMIT_EXCEEDED", Constants.Judge.STATUS_MEMORY_LIMIT_EXCEEDED);
        put("IDLENESS_LIMIT_EXCEEDED", Constants.Judge.STATUS_RUNTIME_ERROR);
        put("SECURITY_VIOLATED", Constants.Judge.STATUS_RUNTIME_ERROR);
        put("CRASHED", Constants.Judge.STATUS_SYSTEM_ERROR);
        put("INPUT_PREPARATION_CRASHED", Constants.Judge.STATUS_SYSTEM_ERROR);
        put("CHALLENGED", Constants.Judge.STATUS_SYSTEM_ERROR);
        put("SKIPPED", Constants.Judge.STATUS_SYSTEM_ERROR);
        put("TESTING", Constants.Judge.STATUS_JUDGING);
        put("REJECTED", Constants.Judge.STATUS_SYSTEM_ERROR);
    }};


    @Override
    public Map<String, Object> submit(String username, String password, String problemId, String language, String userCode) throws Exception {

        if (problemId == null || userCode == null) {
            return null;
        }

        HttpRequest httpRequest = HttpUtil.createGet(IMAGE_HOST);
        httpRequest.setConnectionTimeout(60000);
        httpRequest.setReadTimeout(60000);
        httpRequest.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.101 Safari/537.36 Edg/91.0.864.48");
        HttpResponse httpResponse = httpRequest.execute();
        String homePage = httpResponse.body();
        if (!homePage.contains("/logout\">") || !homePage.contains("<a href=\"/profile/" + username + "\"")) {
            Map<String, Object> loginUtils = getLoginUtils(username, password);
            int status = (int) loginUtils.get("status");
            if (status != HttpStatus.SC_MOVED_TEMPORARILY) {
                log.error("进行题目提交时发生错误：登录失败，可能原因账号或密码错误，登录失败！" + CodeForcesJudge.class.getName() + "，题号:" + problemId);
                return null;
            }
        } else {
            this.cookies = httpResponse.getCookies();
        }

        String contestId;
        String problemNum;
        if (NumberUtil.isInteger(problemId)) {
            contestId = ReUtil.get("([0-9]+)[0-9]{2}", problemId, 1);
            problemNum = ReUtil.get("[0-9]+([0-9]{2})", problemId, 1);
        } else {
            contestId = ReUtil.get("([0-9]+)[A-Z]{1}[0-9]{0,1}", problemId, 1);
            problemNum = ReUtil.get("[0-9]+([A-Z]{1}[0-9]{0,1})", problemId, 1);
        }
        long nowTime = DateUtil.currentSeconds();

        try {
            submitCode(contestId, problemNum, getLanguage(language), userCode);
        } catch (HttpException e) {
            // 如果提交出现403可能是cookie失效了，再执行登录，重新提交
            this.cookies = null;
            Map<String, Object> loginUtils = getLoginUtils(username, password);
            int status = (int) loginUtils.get("status");
            if (status != HttpStatus.SC_MOVED_TEMPORARILY) {
                log.error("CF进行题目提交时发生错误：登录失败，可能原因账号或密码错误，登录失败！" + CodeForcesJudge.class.getName() + "，题号:" + problemId);
                return null;
            }
            submitCode(contestId, problemNum, getLanguage(language), userCode);
        }


        try {
            TimeUnit.MILLISECONDS.sleep(3000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // 获取提交的题目id
        Long maxRunId = getMaxRunId(username, contestId, problemNum, problemId, nowTime);

        return MapUtil.builder(new HashMap<String, Object>())
                .put("runId", maxRunId)
                .put("cookies", null)
                .map();
    }

    @SuppressWarnings("unchecked")
    private Long getMaxRunId(String username, String contestNum, String problemNum, String problemId, long nowTime) {
        int retryNum = 0;
        // 防止cf的nginx限制访问频率，重试5次
        while (retryNum != 10) {
            HttpResponse httpResponse = getSubmissionResult(username, 10);
            if (httpResponse.getStatus() == 200) {
                try {
                    Map<String, Object> json = JSONUtil.parseObj(httpResponse.body());
                    List<Map<String, Object>> results = (List<Map<String, Object>>) json.get("result");
                    for (Map<String, Object> result : results) {
                        Long runId = Long.valueOf(result.get("id").toString());
                        long creationTimeSeconds = Long.parseLong(result.get("creationTimeSeconds").toString());
                        if (creationTimeSeconds < nowTime && retryNum < 8) {
                            continue;
                        }
                        Map<String, Object> problem = (Map<String, Object>) result.get("problem");
                        if (contestNum.equals(problem.get("contestId").toString()) &&
                                problemNum.equals(problem.get("index").toString())) {
                            return runId;
                        }
                    }
                } catch (Exception e) {
                    log.error("进行题目获取runID发生错误：获取提交ID失败，" + CodeForcesJudge.class.getName()
                            + "，题号:" + problemId + "，异常描述：" + e);
                    return -1L;
                }
            }
            try {
                TimeUnit.MILLISECONDS.sleep(3000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            retryNum++;
        }
        return -1L;
    }

    // CF的这个接口有每两秒的访问限制，所以需要加锁，保证只有一次查询
    public static synchronized HttpResponse getSubmissionResult(String username, Integer count) {
        String url = HOST + String.format(SUBMISSION_RESULT_URL, username, count);
        return HttpUtil.createGet(url)
                .timeout(60000)
                .execute();
    }

    @Override
    public Map<String, Object> result(Long submitId, String username, String password, String cookies) {

        String resJson = getSubmissionResult(username, 1000).body();

        JSONObject jsonObject = JSONUtil.parseObj(resJson);

        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("status", Constants.Judge.STATUS_JUDGING.getStatus());

        JSONArray results = (JSONArray) jsonObject.get("result");

        for (Object tmp : results) {
            JSONObject result = (JSONObject) tmp;
            long runId = Long.parseLong(result.get("id").toString());
            if (runId == submitId) {
                String verdict = (String) result.get("verdict");
                Constants.Judge statusType = statusMap.get(verdict);
                if (statusType == Constants.Judge.STATUS_JUDGING) {
                    return MapUtil.builder(new HashMap<String, Object>())
                            .put("status", statusType.getStatus()).build();
                }
                resultMap.put("time", result.get("timeConsumedMillis"));
                resultMap.put("memory", (int) result.get("memoryConsumedBytes") / 1024);
                Constants.Judge resultStatus = statusMap.get(verdict);
                if (resultStatus == Constants.Judge.STATUS_COMPILE_ERROR) {

                    String html = HttpUtil.createGet(HOST)
                            .timeout(30000).execute().body();
                    String csrfToken = ReUtil.get("data-csrf='(\\w+)'", html, 1);

                    String ceJson = HttpUtil.createPost(HOST + CE_INFO_URL)
                            .form(MapUtil
                                    .builder(new HashMap<String, Object>())
                                    .put("csrf_token", csrfToken)
                                    .put("submissionId", submitId.toString()).map())
                            .timeout(30000)
                            .execute()
                            .body();
                    JSONObject CEInfoJson = JSONUtil.parseObj(ceJson);
                    String CEInfo = CEInfoJson.getStr("checkerStdoutAndStderr#1");

                    resultMap.put("CEInfo", CEInfo);
                }
                resultMap.put("status", resultStatus.getStatus());
                return resultMap;
            }
        }
        return resultMap;
    }

    public String getCsrfToken(String url) {
        HttpRequest request = HttpUtil.createGet(url);
        request.cookie(this.cookies);
        HttpResponse response = request.execute();
        this.cookies = response.getCookies();
        String body = response.body();
        return ReUtil.get("data-csrf='(\\w+)'", body, 1);
    }

    @Override
    public Map<String, Object> getLoginUtils(String username, String password) {
        String csrf_token = getCsrfToken(IMAGE_HOST + LOGIN_URL);
        HttpRequest httpRequest = new HttpRequest(IMAGE_HOST + LOGIN_URL);
        httpRequest.setConnectionTimeout(60000);
        httpRequest.setReadTimeout(60000);
        httpRequest.setMethod(Method.POST);
        httpRequest.cookie(this.cookies);
        HashMap<String, Object> hashMap = new HashMap<>();
        hashMap.put("csrf_token", csrf_token);
        hashMap.put("action", "enter");
        hashMap.put("ftaa", "");
        hashMap.put("bfaa", "");
        hashMap.put("handleOrEmail", username);
        hashMap.put("password", password);
        hashMap.put("remember", "on");
        httpRequest.form(hashMap);
        HttpResponse response = httpRequest.execute();
        this.cookies = response.getCookies();
        return MapUtil.builder(new HashMap<String, Object>()).put("status", response.getStatus()).map();
    }

    protected String getSubmitUrl(String contestNum) {
        return IMAGE_HOST + SUBMIT_URL;
    }

    public void submitCode(String contestId, String problemID, String languageID, String code) throws HttpException {
        String csrfToken = getCsrfToken(getSubmitUrl(contestId));
        HashMap<String, Object> paramMap = new HashMap<>();
        paramMap.put("csrf_token", csrfToken);
        paramMap.put("_tta", 140);
        paramMap.put("bfaa", "f1b3f18c715565b589b7823cda7448ce");
        paramMap.put("ftaa", "");
        paramMap.put("action", "submitSolutionFormSubmitted");
        paramMap.put("submittedProblemIndex", problemID);
        paramMap.put("contestId", contestId);
        paramMap.put("programTypeId", languageID);
        paramMap.put("tabsize", 4);
        paramMap.put("source", code + getRandomBlankString());
        paramMap.put("sourceCodeConfirmed", true);
        paramMap.put("doNotShowWarningAgain", "on");
        HttpRequest request = HttpUtil.createPost(getSubmitUrl(contestId) + "?csrf_token=" + csrfToken);
        request.setConnectionTimeout(60000);
        request.setReadTimeout(60000);
        request.form(paramMap);
        request.cookie(this.cookies);
        request.header("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/89.0.4389.90 Safari/537.36");
        HttpResponse response = request.execute();
        if (response.getStatus() != HttpStatus.SC_MOVED_TEMPORARILY) {
            if (response.body().contains("error for__programTypeId")) {
                String log = String.format("Codeforces[%s] [%s]:Failed to submit code, caused by `Language Rejected`", contestId, problemID);
                throw new IllegalArgumentException(log);
            }
            if (response.body().contains("error for__source")) {
                String log = String.format("Codeforces[%s] [%s]:Failed to submit code, caused by `Source Code Error`", contestId, problemID);
                throw new IllegalArgumentException(log);
            }
            String log = String.format("Codeforces[%s] [%s]:Failed to submit code, caused by `403 Forbidden`", contestId, problemID);
            throw new HttpException(log);
        }
    }

    @Override
    public String getLanguage(String language) {
        if (language.startsWith("GNU GCC C11")) {
            return "43";
        } else if (language.startsWith("Clang++17 Diagnostics")) {
            return "52";
        } else if (language.startsWith("GNU G++14")) {
            return "50";
        } else if (language.startsWith("GNU G++17")) {
            return "54";
        } else if (language.startsWith("Microsoft Visual C++ 2010")) {
            return "2";
        } else if (language.startsWith("Microsoft Visual C++ 2017")) {
            return "59";
        } else if (language.startsWith("C# 8, .NET Core")) {
            return "65";
        } else if (language.startsWith("C# Mono")) {
            return "9";
        } else if (language.startsWith("D DMD32")) {
            return "28";
        } else if (language.startsWith("Go")) {
            return "32";
        } else if (language.startsWith("Haskell GHC")) {
            return "12";
        } else if (language.startsWith("Java 11")) {
            return "60";
        } else if (language.startsWith("Java 1.8")) {
            return "36";
        } else if (language.startsWith("Kotlin")) {
            return "48";
        } else if (language.startsWith("OCaml")) {
            return "19";
        } else if (language.startsWith("Delphi")) {
            return "3";
        } else if (language.startsWith("Free Pascal")) {
            return "4";
        } else if (language.startsWith("PascalABC.NET")) {
            return "51";
        } else if (language.startsWith("Perl")) {
            return "13";
        } else if (language.startsWith("PHP")) {
            return "6";
        } else if (language.startsWith("Python 2")) {
            return "7";
        } else if (language.startsWith("Python 3")) {
            return "31";
        } else if (language.startsWith("PyPy 2")) {
            return "40";
        } else if (language.startsWith("PyPy 3")) {
            return "41";
        } else if (language.startsWith("Ruby")) {
            return "67";
        } else if (language.startsWith("Rust")) {
            return "49";
        } else if (language.startsWith("Scala")) {
            return "20";
        } else if (language.startsWith("JavaScript")) {
            return "34";
        } else if (language.startsWith("Node.js")) {
            return "55";
        } else {
            return null;
        }
    }

    protected String getRandomBlankString() {
        StringBuilder string = new StringBuilder("\n");
        int random = new Random().nextInt(Integer.MAX_VALUE);
        while (random > 0) {
            string.append(random % 2 == 0 ? ' ' : '\t');
            random /= 2;
        }
        return string.toString();
    }

}
