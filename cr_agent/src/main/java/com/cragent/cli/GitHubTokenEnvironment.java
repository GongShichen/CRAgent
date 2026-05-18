package com.cragent.cli;

import com.cragent.config.Settings;
import com.cragent.util.Jsons;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

public final class GitHubTokenEnvironment {
    private GitHubTokenEnvironment() {
    }

    public static CheckResult checkRepo(Settings settings, String repo) {
        if (!settings.hasGithubCredentials()) {
            return new CheckResult(false, null, "GITHUB_TOKEN 未配置。");
        }
        HttpClient client = HttpClient.newHttpClient();
        String login = null;
        try {
            HttpResponse<String> user = client.send(request(settings, "https://api.github.com/user"), HttpResponse.BodyHandlers.ofString());
            if (user.statusCode() >= 200 && user.statusCode() < 300) {
                Map<String, Object> body = Jsons.parseMap(user.body());
                Object value = body.get("login");
                login = value == null ? null : String.valueOf(value);
            }
            HttpResponse<String> repository = client.send(request(settings, "https://api.github.com/repos/" + repo), HttpResponse.BodyHandlers.ofString());
            if (repository.statusCode() >= 200 && repository.statusCode() < 300) {
                return new CheckResult(true, login, "GITHUB_TOKEN 可以访问 " + repo + "。");
            }
            if (repository.statusCode() == 404) {
                String who = login == null ? "当前 GITHUB_TOKEN" : "当前 GITHUB_TOKEN 认证为 " + login;
                return new CheckResult(false, login, who + "，但无法访问 " + repo + "。如果这是 private repo，请在 fine-grained token 的 Repository access 中加入该仓库。");
            }
            return new CheckResult(false, login, "GITHUB_TOKEN 访问 " + repo + " 失败：HTTP " + repository.statusCode() + " " + repository.body());
        } catch (IOException e) {
            return new CheckResult(false, login, "检查 GITHUB_TOKEN 时网络失败：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CheckResult(false, login, "检查 GITHUB_TOKEN 被中断。");
        }
    }

    private static HttpRequest request(Settings settings, String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + settings.githubToken())
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .build();
    }

    public record CheckResult(boolean accessible, String login, String message) {
    }
}
