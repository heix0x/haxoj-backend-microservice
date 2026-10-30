/*
 * @Author: hax 3152409286@qq.com
 * @Date: 2026-09-14 14:58:38
 * @LastEditors: hax 3152409286@qq.com
 * @LastEditTime: 2026-10-10 09:33:14
 * @FilePath: \haxoj\haxoj-backend\src\main\java\com\hax\haxojbackend\judge\codesandbox\impl\RemoteCodeSandbox.java
 * @Description: 这是默认设置,请设置`customMade`, 打开koroFileHeader查看配置 进行设置: https://github.com/OBKoro1/koro1FileHeader/wiki/%E9%85%8D%E7%BD%AE
 */
package com.hax.haxojbackend.judge.codesandbox.impl;

import cn.hutool.crypto.SecureUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONUtil;
import com.hax.haxojbackend.common.ErrorCode;
import com.hax.haxojbackend.exception.SystemException;
import com.hax.haxojbackend.judge.codesandbox.CodeSandbox;
import com.hax.haxojbackend.judge.codesandbox.model.ExecuteCodeRequest;
import com.hax.haxojbackend.judge.codesandbox.model.ExecuteCodeResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 远程代码沙箱：不自己执行代码，而是把判题请求转发给独立部署的沙箱服务（haxoj-code-sandbox）。
 *
 * <p>为什么这三个值原来是 {@code static final} 而现在改成 {@code @Value} 实例字段：
 * 沙箱跑在虚拟机上，而虚拟机 IP 会变（历史上 209 → 210 → 221）。写死在代码里意味着
 * IP 一变就要改 Java 源码、重新编译、重新打包 —— 而地址属于「环境」，不属于「代码」。
 * 现在它和数据库密码一样，从配置来（{@code codesandbox.execute-url} 等），
 * 换机器只改环境变量，一行代码都不用动。</p>
 *
 * <p>也因此这个类必须是 Spring bean：{@code @Value} 只对容器创建的对象生效，
 * 自己 {@code new} 出来的实例拿不到注入值。</p>
 */
@Slf4j
@Component("remote")
public class RemoteCodeSandbox implements CodeSandbox {

    /**
     * 鉴权请求头名称，必须和沙箱服务端保持一致。
     */
    @Value("${codesandbox.auth-header:auth}")
    private String authRequestHeader;

    /**
     * 鉴权密钥，必须和沙箱服务端保持一致。
     * <p>
     * ⚠️ 不在网络上传明文，实际传的是它的 MD5 摘要。
     */
    @Value("${codesandbox.auth-secret:secretKey}")
    private String authRequestSecret;

    /**
     * 沙箱服务的调用地址，例如 {@code http://192.168.221.45:8080/sandbox/execute}。
     * <p>
     * 默认值只是「最后一次已知能用的地址」，不是应该写死的值 —— 真实值由
     * {@code SANDBOX_EXECUTE_URL} 环境变量注入（见 ops/env/dev.env）。
     */
    @Value("${codesandbox.execute-url:http://192.168.221.45:8080/sandbox/execute}")
    private String sandboxExecuteUrl;

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        String requestBody = JSONUtil.toJsonStr(executeCodeRequest);

        HttpResponse httpResponse = HttpRequest.post(sandboxExecuteUrl)
                // 传密钥的 MD5 摘要，而不是明文密钥
                .header(authRequestHeader, SecureUtil.md5(authRequestSecret))
                .body(requestBody, "application/json")
                .execute();

        if (!httpResponse.isOk()) {
            // 沙箱不可用属于「换时间可能就好的系统异常」：不能当业务失败 ack 掉，
            // 否则提交会永远卡在「判题中」。抛 SystemException 让消费者走死信 + 重置状态。
            throw new SystemException(ErrorCode.SYSTEM_ERROR, "调用代码沙箱失败：" + httpResponse.body());
        }

        ExecuteCodeResponse executeCodeResponse = JSONUtil.toBean(httpResponse.body(), ExecuteCodeResponse.class);
        log.info("远程代码沙箱执行结果：{}", executeCodeResponse);
        return executeCodeResponse;
    }

}