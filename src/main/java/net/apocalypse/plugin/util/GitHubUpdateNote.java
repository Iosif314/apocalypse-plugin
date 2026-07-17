package net.apocalypse.plugin.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** GitHub 저장소의 가장 최근 커밋 여러 개를 가져와 명령어 발신자에게 보여준다. */
public final class GitHubUpdateNote {

    private static final int COMMIT_COUNT = 5;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private GitHubUpdateNote() {
    }

    /**
     * repo는 "소유자/저장소" 형식. GitHub API 요청은 메인 스레드를 막지 않도록 비동기로 처리하고,
     * 결과 메시지는 다시 메인 스레드로 돌아와서 sender에게 전송한다.
     */
    public static void fetchLatestCommit(Plugin plugin, CommandSender sender, String repo) {
        sender.sendMessage(ColorUtil.parse("&7최근 커밋 정보를 가져오는 중..."));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<String> lines;
            try {
                lines = fetch(repo);
            } catch (Exception e) {
                lines = List.of("&c최근 커밋 정보를 가져오지 못했습니다: " + e.getMessage());
            }
            List<String> finalLines = lines;
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (String line : finalLines) {
                    sender.sendMessage(ColorUtil.parse(line));
                }
            });
        });
    }

    private static List<String> fetch(String repo) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.github.com/repos/" + repo + "/commits?per_page=" + COMMIT_COUNT))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Apocalypse-Plugin")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return List.of("&cGitHub API 응답 오류 (상태 코드 " + response.statusCode() + ")");
        }

        JsonArray commits = JsonParser.parseString(response.body()).getAsJsonArray();
        if (commits.isEmpty()) {
            return List.of("&e저장소에 커밋이 없습니다.");
        }

        List<String> lines = new ArrayList<>();
        lines.add("&6=== 최근 커밋 " + commits.size() + "개 ===");
        for (int i = 0; i < commits.size(); i++) {
            lines.add(formatCommitLine(commits.get(i).getAsJsonObject()));
        }
        return lines;
    }

    private static String formatCommitLine(JsonObject entry) {
        String sha = entry.get("sha").getAsString();
        String shortSha = sha.substring(0, Math.min(7, sha.length()));

        JsonObject commit = entry.getAsJsonObject("commit");
        String fullMessage = commit.get("message").getAsString();
        String firstLine = fullMessage.split("\n", 2)[0];

        JsonObject author = commit.getAsJsonObject("author");
        String authorName = author.get("name").getAsString();
        String formattedDate = formatDate(author.get("date").getAsString());

        return "&7" + shortSha + " &8· &f" + firstLine + " &8· &7" + authorName + " (" + formattedDate + ")";
    }

    private static String formatDate(String isoDate) {
        try {
            return DATE_FORMAT.format(Instant.parse(isoDate));
        } catch (Exception e) {
            return isoDate;
        }
    }
}
