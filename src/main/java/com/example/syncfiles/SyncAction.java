package com.example.syncfiles;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class SyncAction extends AnAction {
    public SyncAction() {
        super("Sync Files");
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            Messages.showErrorDialog("没有打开的项目", "错误");
            return;
        }
        syncFiles(project);
    }

    public void syncFiles(Project project) {
        SyncFilesConfig config = SyncFilesConfig.getInstance(project);
        List<Mapping> mappings = config.getMappings(); // 更新为新 Mapping 类
        if (mappings.isEmpty()) {
            Messages.showWarningDialog("没有配置映射。请在 '设置 > SyncFiles 设置' 中检查。", "警告");
            return;
        }

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "正在同步 GitHub 文件", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setText("开始同步...");
                try {
                    for (Mapping mapping : mappings) {
                        indicator.setText("正在同步: " + mapping.sourceUrl);
                        Path targetPath = mapping.targetPath.startsWith("/") || mapping.targetPath.contains(":")
                                ? Paths.get(mapping.targetPath)
                                : Paths.get(project.getBasePath(), mapping.targetPath);
                        System.out.println("目标路径解析: " + targetPath);
                        if (mapping.sourceUrl.contains("raw.githubusercontent.com")) {
                            fetchFile(mapping.sourceUrl, targetPath);
                        } else if (mapping.sourceUrl.contains("/tree/")) {
                            fetchDirectory(mapping.sourceUrl, targetPath, project.getBasePath());
                        } else {
                            Messages.showWarningDialog("不支持的 URL 格式: " + mapping.sourceUrl, "警告");
                        }
                    }

                    ApplicationManager.getApplication().invokeLater(() -> {
                        Messages.showInfoMessage("同步成功完成！", "成功");
                        new Timer().schedule(new TimerTask() {
                            @Override
                            public void run() {
                                ApplicationManager.getApplication().invokeLater(() -> {
                                    project.getBaseDir().refresh(false, true);
                                });
                            }
                        }, 1000);
                    });

                } catch (Exception ex) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        Messages.showErrorDialog("同步失败: " + ex.getMessage(), "错误");
                    });
                    ex.printStackTrace();
                }
            }
        });
    }

    private void fetchFile(String url, Path targetPath) throws IOException, InterruptedException {
        System.out.println("正在获取文件: " + url);
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 302) {
            String redirectUrl = response.headers().firstValue("location").orElseThrow(() -> new IOException("无重定向地址"));
            System.out.println("重定向到: " + redirectUrl);
            fetchFile(redirectUrl, targetPath);
            return;
        }

        if (response.statusCode() != 200) {
            throw new IOException("获取文件失败，状态码: " + response.statusCode());
        }

        Files.createDirectories(targetPath.getParent());
        Files.writeString(targetPath, response.body());
        System.out.println("文件保存到: " + targetPath);
    }

    private void fetchDirectory(String repoUrl, Path targetPath, String workspacePath) throws IOException, InterruptedException {
        System.out.println("原始 repoUrl: " + repoUrl);
        String normalizedUrl = repoUrl.trim().replaceAll("^https?://(www\\.)?github\\.com/", "https://github.com/");

        if (!normalizedUrl.contains("/tree/")) {
            throw new IllegalArgumentException("无效的 GitHub 目录 URL，缺少 /tree/: " + repoUrl);
        }

        String[] parts = normalizedUrl.split("/tree/");
        if (parts.length != 2) {
            throw new IllegalArgumentException("无效的 URL 格式，期望 /tree/: " + repoUrl);
        }
        String repoPart = parts[0];
        String branchAndPath = parts[1];
        System.out.println("仓库部分: " + repoPart + ", 分支和路径: " + branchAndPath);

        String[] pathSegments = branchAndPath.split("/", 2);
        String branch = pathSegments[0];
        String subPath = pathSegments.length > 1 ? java.net.URLDecoder.decode(pathSegments[1], "UTF-8") : "";
        System.out.println("分支: " + branch + ", 子路径: " + subPath);

        String repoRoot = repoPart.replace("https://github.com/", "https://codeload.github.com/");
        String zipUrl = String.format("%s/zip/refs/heads/%s", repoRoot, branch);
        System.out.println("从以下地址获取 ZIP: " + zipUrl);

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(zipUrl)).build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        System.out.println("HTTP 状态: " + response.statusCode());

        if (response.statusCode() != 200) {
            throw new IOException("获取 ZIP 失败，状态码: " + response.statusCode());
        }

        Path zipPath = Paths.get(workspacePath, "temp-repo.zip");
        System.out.println("保存 ZIP 到: " + zipPath);
        Files.createDirectories(zipPath.getParent());

        try (InputStream in = response.body(); FileOutputStream fos = new FileOutputStream(zipPath.toFile())) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
        }

        if (!Files.exists(zipPath) || Files.size(zipPath) == 0) {
            throw new IOException("下载的 ZIP 文件为空或丢失: " + zipPath);
        }

        Path tempExtractPath = Paths.get(workspacePath, "temp-extract");
        System.out.println("解压到: " + tempExtractPath);
        unzip(zipPath, tempExtractPath);

        Path sourceDir;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tempExtractPath)) {
            Path firstDir = stream.iterator().next();
            sourceDir = firstDir;
            System.out.println("顶级目录: " + sourceDir);
        }

        if (!subPath.isEmpty()) {
            sourceDir = sourceDir.resolve(subPath);
            System.out.println("解析的子路径: " + sourceDir);
            if (!Files.exists(sourceDir)) {
                throw new IOException("ZIP 中不存在子路径: " + subPath);
            }
        }

        System.out.println("从以下位置合并: " + sourceDir + " 到: " + targetPath);
        mergeDirectory(sourceDir, targetPath);

        try {
            Files.deleteIfExists(zipPath);
            Files.walk(tempExtractPath).sorted((p1, p2) -> -p1.compareTo(p2)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    System.err.println("删除失败: " + p);
                }
            });
        } catch (IOException e) {
            System.err.println("清理失败: " + e.getMessage());
        }

        System.out.println("目录已同步到: " + targetPath);
    }

    private void unzip(Path zipPath, Path extractPath) throws IOException {
        System.out.println("解压: " + zipPath + " 到: " + extractPath);
        Files.createDirectories(extractPath);
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path outPath = extractPath.resolve(entry.getName());
                System.out.println("处理 ZIP 条目: " + entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(outPath);
                } else {
                    Files.createDirectories(outPath.getParent());
                    try (FileOutputStream fos = new FileOutputStream(outPath.toFile())) {
                        byte[] buffer = new byte[1024];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private void mergeDirectory(Path source, Path target) throws IOException {
        System.out.println("合并源: " + source + " 到目标: " + target);
        if (Files.isDirectory(source)) {
            if (Files.notExists(target)) {
                Files.createDirectories(target);
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(source)) {
                for (Path entry : stream) {
                    mergeDirectory(entry, target.resolve(entry.getFileName()));
                }
            }
        } else {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        e.getPresentation().setEnabledAndVisible(project != null);
    }
}