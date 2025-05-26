// File: LoadSmartWorkflowAction.java
package com.example.syncfiles; // 或者你的包名

import com.example.syncfiles.config.smartworkflow.SmartWorkflowRootConfig;
import com.example.syncfiles.logic.SmartWorkflowService; // 将创建的服务类
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import javax.swing.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class LoadSmartWorkflowAction extends AnAction {

    private static final String DEFAULT_YAML_URL = "https://raw.githubusercontent.com/sammiler/CodeConf/refs/heads/main/Cpp/SyncFiles/Clion/workflow.yaml"; // <<< 替换这个
    private static final Icon CUSTOM_WORKFLOW_ICON = IconLoader.getIcon("/icons/automatic.svg", LoadSmartWorkflowAction.class);
    public LoadSmartWorkflowAction() {
        super("Load Smart Workflow...", "Load and apply configuration from a YAML URL", CUSTOM_WORKFLOW_ICON);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();

        String inputUrl = Messages.showInputDialog(
                project,
                "Enter YAML Configuration URL:",
                "Load Smart Workflow",
                AllIcons.General.Web,
                DEFAULT_YAML_URL,
                null
        );

        if (StringUtil.isEmpty(inputUrl)) {
            return;
        }

        final String finalUrl = inputUrl; // For use in lambda

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Loading Smart Workflow: " + StringUtil.trimMiddle(finalUrl, 50), true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                indicator.setText("Downloading YAML from: " + finalUrl);
                SmartWorkflowService.LOG.info("Starting Smart Workflow from URL: " + finalUrl);

                try {
                    String yamlContent = downloadContent(finalUrl, indicator);
                    if (yamlContent == null) {
                        // downloadContent 内部会记录错误
                        SmartWorkflowService.LOG.warn("YAML content download failed or returned null.");
                        ApplicationManager.getApplication().invokeLater(() ->
                                Messages.showErrorDialog(project, "Failed to download YAML configuration from: " + finalUrl, "Download Error")
                        );
                        return;
                    }
                    indicator.setText("YAML downloaded. Processing...");

                    SmartWorkflowService workflowService; // project 可以为 null
                    if (project != null) {
                        workflowService = project.getService(SmartWorkflowService.class);
                        // ★★★ 调用服务处理 YAML 并触发下载 ★★★
                        workflowService.prepareWorkflowFromYaml(yamlContent);
                    }


                } catch (MalformedURLException mue) {
                    SmartWorkflowService.LOG.warn("Invalid URL provided for YAML configuration: " + finalUrl, mue);
                    ApplicationManager.getApplication().invokeLater(() ->
                            Messages.showErrorDialog(project, "The provided URL is invalid: " + finalUrl + "\nError: " + mue.getMessage(), "Invalid URL")
                    );
                }
                catch (IOException ioe) {
                    SmartWorkflowService.LOG.warn("IOException during YAML download from " + finalUrl, ioe);
                    ApplicationManager.getApplication().invokeLater(() ->
                            Messages.showErrorDialog(project, "Error downloading YAML configuration: " + ioe.getMessage(), "Download I/O Error")
                    );
                } catch (Exception ex) {
                    SmartWorkflowService.LOG.error("Unexpected error during Smart Workflow execution from URL " + finalUrl, ex);
                    ApplicationManager.getApplication().invokeLater(() ->
                            Messages.showErrorDialog(project, "An unexpected error occurred: " + ex.getMessage(), "Workflow Error")
                    );
                }
            }
        });
    }

    private String downloadContent(String urlString, ProgressIndicator indicator) throws IOException {
        indicator.setText2("Connecting to " + urlString);
        URL url = URL.of(URI.create(urlString),null); // 使用 URL.of 替代构造函数
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(15000); // 15 秒
        connection.setReadTimeout(60000);    // 60 秒
        indicator.checkCanceled(); // 允许取消

        int responseCode = connection.getResponseCode();
        SmartWorkflowService.LOG.info("HTTP Response Code for " + urlString + ": " + responseCode);

        if (responseCode == HttpURLConnection.HTTP_OK) {
            indicator.setText2("Reading response...");
            try (InputStream inputStream = connection.getInputStream();
                 Scanner scanner = new Scanner(inputStream, StandardCharsets.UTF_8.name())) {
                indicator.checkCanceled();
                return scanner.useDelimiter("\\A").next();
            }
        } else {
            String errorMsg = "Failed to download from " + urlString + ". HTTP Status: " + responseCode;
            try (InputStream errorStream = connection.getErrorStream()) {
                if (errorStream != null) {
                    try (Scanner scanner = new Scanner(errorStream, StandardCharsets.UTF_8.name())) {
                        errorMsg += "\nServer Response:\n" + scanner.useDelimiter("\\A").next();
                    }
                }
            } catch (Exception e) {
                // ignore, just couldn't read error stream
            }
            SmartWorkflowService.LOG.warn(errorMsg);
            // 让调用者处理错误通知，这里只抛出异常
            throw new IOException(errorMsg);
        }
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(true);
    }
}
