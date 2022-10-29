/*
 * Copyright 2021-2022 KasukuSakura Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/KasukuSakura/mirai-login-solver-sakura/blob/main/LICENSE
 */

package cihelper;

import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@SuppressWarnings({"ConstantConditions", "SwitchStatementWithTooFewBranches"})
public class CiHelper {
    public static void main(String[] args) throws Throwable {
        System.out.println(Arrays.toString(args));
        if (args.length == 0) return;
        var httpc = HttpClient.newHttpClient();
        var token = System.getenv("TOKEN");

        switch (args[0]) {
            case "uploadAssets" -> {
                var uploadUrl = System.getenv("UPLOAD_URL");
                uploadUrl = uploadUrl.replaceAll("\\??\\{.*}$", "");

                for (var att : new File("build/output").listFiles()) {
                    if (att.isDirectory()) continue;

                    var urlxc = uploadUrl + "?name=" + URLEncoder.encode(att.getName(), StandardCharsets.UTF_8);
                    System.out.println("Processing " + att + " to " + urlxc);

                    var rspx = httpc.send(HttpRequest.newBuilder()
                                    .uri(URI.create(urlxc))
                                    .header("Accept", "application/vnd.github+json")
                                    .header("Content-Type", "application/octet-stream")
                                    .header("Authorization", "Bearer " + token)
                                    .POST(HttpRequest.BodyPublishers.ofFile(att.toPath()))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString()
                    );
                    if (rspx.statusCode() != 201) {
                        throw new RuntimeException(rspx.body());
                    }
                }
            }
        }
    }
}
