package com.javastudio.manager;

import com.javastudio.JavaStudioPlugin;
import com.javastudio.compiler.CompileResult;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

public class PluginExporter {

    private final JavaStudioPlugin hostPlugin;
    private final File exportsFolder;

    public PluginExporter(JavaStudioPlugin hostPlugin) {
        this.hostPlugin = hostPlugin;
        this.exportsFolder = new File(hostPlugin.getDataFolder(), "exports");
        if (!exportsFolder.exists()) {
            exportsFolder.mkdirs();
        }
    }

    public File exportProject(String projectName, StringBuilder errorLog) {
        return exportProject(projectName, false, null, errorLog);
    }

    public File exportProject(String projectName, boolean obfuscate, String licenseKey, StringBuilder errorLog) {
        File projectDir = new File(hostPlugin.getPluginManager().getProjectsFolder(), projectName);
        if (!projectDir.exists()) {
            if (errorLog != null) errorLog.append("Không tìm thấy dự án ").append(projectName);
            return null;
        }

        File metaFile = new File(projectDir, "metadata.yml");
        if (!metaFile.exists()) {
            if (errorLog != null) errorLog.append("Thiếu file metadata.yml trong dự án!");
            return null;
        }

        YamlConfiguration meta = YamlConfiguration.loadConfiguration(metaFile);
        String name = meta.getString("name", projectName);
        String version = meta.getString("version", "1.0.0");
        String mainClass = meta.getString("main");
        String author = meta.getString("author", "JavaStudio Developer");
        String description = meta.getString("description", "Plugin tạo bởi JavaStudio");

        if (mainClass == null) {
            if (errorLog != null) errorLog.append("metadata.yml thiếu trường 'main'!");
            return null;
        }

        CompileResult compileResult = hostPlugin.getPluginManager().compileProject(projectName);
        if (compileResult == null || !compileResult.isSuccess()) {
            if (errorLog != null) {
                errorLog.append(compileResult != null ? compileResult.getFormattedErrors() : "Lỗi biên dịch không rõ!");
            }
            return null;
        }

        File outputJar = new File(exportsFolder, name + "-" + version + ".jar");

        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(new Attributes.Name("Created-By"), "JavaStudio Exporter" + (obfuscate ? " (Protected)" : ""));

        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(outputJar), manifest)) {
            for (Map.Entry<String, byte[]> entry : compileResult.getByteCodes().entrySet()) {
                String entryPath = entry.getKey().replace('.', '/') + ".class";
                jos.putNextEntry(new JarEntry(entryPath));
                byte[] bytes = entry.getValue();
                if (obfuscate) {
                    bytes = BytecodeObfuscator.obfuscateClass(bytes, name);
                }
                jos.write(bytes);
                jos.closeEntry();
            }

            String[] coreRuntimeClasses = {
                "com/javastudio/export/StandaloneBootstrap.class",
                "com/javastudio/export/LicenseManager.class",
                "com/javastudio/export/LicenseManager$LicensePayload.class",
                "com/javastudio/export/LicenseManager$VerificationResult.class",
                "com/javastudio/api/DynamicPlugin.class",
                "com/javastudio/api/DynamicCommand.class",
                "com/javastudio/api/JavaContext.class",
                "com/javastudio/api/Persist.class",
                "com/javastudio/api/QuickScript.class",
                "com/javastudio/api/item/ItemBuilder.class",
                "com/javastudio/api/gui/SmartGui.class",
                "com/javastudio/api/gui/SmartGui$GuiItem.class",
                "com/javastudio/utils/MessageUtil.class",
                "com/javastudio/utils/MessageUtil$AdventureBridge.class",
                "com/javastudio/utils/PlatformUtil.class",
                "com/javastudio/utils/PlatformUtil$1.class",
                "com/javastudio/utils/PlatformUtil$2.class",
                "com/javastudio/utils/PlatformUtil$TaskHandle.class",
                "com/javastudio/utils/SmartScheduler.class",
                "com/javastudio/utils/ReflectionUtil.class",
                "com/javastudio/storage/DataStore.class"
            };
            for (String cls : coreRuntimeClasses) {
                embedClass(jos, cls);
            }

            String pluginYmlContent = """
                    name: %NAME%
                    version: '%VERSION%'
                    main: com.javastudio.export.StandaloneBootstrap
                    api-version: '1.20'
                    author: %AUTHOR%
                    description: %DESCRIPTION%
                    folia-supported: true
                    """
                    .replace("%NAME%", name)
                    .replace("%VERSION%", version)
                    .replace("%AUTHOR%", author)
                    .replace("%DESCRIPTION%", description);

            jos.putNextEntry(new JarEntry("plugin.yml"));
            jos.write(pluginYmlContent.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();

            String metaContent = "main: " + mainClass + "\nname: " + name + "\nversion: " + version + "\n";
            jos.putNextEntry(new JarEntry("javastudio_meta.yml"));
            jos.write(metaContent.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();

            if (licenseKey != null && !licenseKey.trim().isEmpty()) {
                jos.putNextEntry(new JarEntry("javastudio_license.key"));
                jos.write(licenseKey.trim().getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();

                if (hostPlugin.getLicenseManager() != null) {
                    var payload = hostPlugin.getLicenseManager().parseLicense(licenseKey);
                    String buyer = payload != null ? payload.buyer() : "Customer";
                    String wm = hostPlugin.getLicenseManager().createWatermarkMetadata(buyer, name);
                    jos.putNextEntry(new JarEntry("META-INF/BUYER-WATERMARK.INF"));
                    jos.write(wm.getBytes(StandardCharsets.UTF_8));
                    jos.closeEntry();
                }
            }

            File configFile = new File(projectDir, "data" + File.separator + "config.yml");
            if (configFile.exists()) {
                jos.putNextEntry(new JarEntry("config.yml"));
                jos.write(java.nio.file.Files.readAllBytes(configFile.toPath()));
                jos.closeEntry();
            }

            hostPlugin.getLogger().info("[Exporter] Xuất thành công plugin độc lập: " + outputJar.getName()
                    + (obfuscate ? " [Obfuscated]" : "") + (licenseKey != null ? " [Licensed]" : ""));
            return outputJar;

        } catch (Exception e) {
            if (errorLog != null) errorLog.append("Lỗi khi ghi file JAR: ").append(e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private void embedClass(JarOutputStream jos, String classPath) {
        try (InputStream in = hostPlugin.getClass().getClassLoader().getResourceAsStream(classPath)) {
            if (in != null) {
                jos.putNextEntry(new JarEntry(classPath));
                in.transferTo(jos);
                jos.closeEntry();
            }
        } catch (Exception ignored) {}
    }

    public File getExportsFolder() {
        return exportsFolder;
    }
}
