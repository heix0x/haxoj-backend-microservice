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
import com.hax.haxojbackend.exception.BusinessException;
import com.hax.haxojbackend.judge.codesandbox.CodeSandbox;
import com.hax.haxojbackend.judge.codesandbox.model.ExecuteCodeRequest;
import com.hax.haxojbackend.judge.codesandbox.model.ExecuteCodeResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * 远程代码沙箱：不自己执行代码，而是把判题请求转发给独立部署的沙箱服务（haxoj-code-sandbox）。
 */
@Slf4j
public class RemoteCodeSandbox implements CodeSandbox {

    /**
     * 鉴权请求头名称，必须和沙箱服务端保持一致。
     */
    private static final String AUTH_REQUEST_HEADER = "auth";

    /**
     * 鉴权密钥，必须和沙箱服务端保持一致。
     * <p>
     * ⚠️ 不在网络上传明文，实际传的是它的 MD5 摘要。
     */
    private static final String AUTH_REQUEST_SECRET = "secretKey";

    /**
     * 沙箱服务的调用地址。
     * <p>
     * ⚠️ 沙箱部署在别的机器（虚拟机）上时，把 localhost 换成那台机器的 IP。
     */
    private static final String SANDBOX_EXECUTE_URL = "http://192.168.221.45:8080/sandbox/execute";

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        String requestBody = JSONUtil.toJsonStr(executeCodeRequest);

        HttpResponse httpResponse = HttpRequest.post(SANDBOX_EXECUTE_URL)
                // 传密钥的 MD5 摘要，而不是明文密钥
                .header(AUTH_REQUEST_HEADER, SecureUtil.md5(AUTH_REQUEST_SECRET))
                .body(requestBody, "application/json")
                .execute();

        if (!httpResponse.isOk()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "调用代码沙箱失败：" + httpResponse.body());
        }

        ExecuteCodeResponse executeCodeResponse = JSONUtil.toBean(httpResponse.body(), ExecuteCodeResponse.class);
        log.info("远程代码沙箱执行结果：{}", executeCodeResponse);
        return executeCodeResponse;
    }

}