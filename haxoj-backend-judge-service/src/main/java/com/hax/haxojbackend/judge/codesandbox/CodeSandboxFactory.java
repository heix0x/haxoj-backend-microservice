package com.hax.haxojbackend.judge.codesandbox;

import com.hax.haxojbackend.judge.codesandbox.impl.ExampleCodeSandbox;
import com.hax.haxojbackend.judge.codesandbox.impl.RemoteCodeSandbox;
import com.hax.haxojbackend.judge.codesandbox.impl.ThirdPartyCodeSandbox;

/**
 * 代码沙箱工厂
 * <p>
 * 根据传入的关键词创建对应的 {@link CodeSandbox} 实例。
 * 支持的关键词（不区分大小写）：
 * <ul>
 * <li><b>example</b> → {@link ExampleCodeSandbox}（示例沙箱，仅做演示）</li>
 * <li><b>remote</b> → {@link RemoteCodeSandbox}（远程沙箱，调用独立部署的判题服务）</li>
 * <li><b>third party</b> / <b>thirdparty</b> / <b>third_party</b> →
 * {@link ThirdPartyCodeSandbox}（第三方沙箱）</li>
 * </ul>
 * 传入 null 或无法识别的关键词时，默认返回 {@link ExampleCodeSandbox}。
 * <p>
 * 可配合 application.yml 中的 {@code codesandbox.type} 配置使用：
 * 
 * <pre>
 * codesandbox:
 *   type: example
 * </pre>
 */
public class CodeSandboxFactory {

    /**
     * 根据关键词创建沙箱实例
     *
     * @param type 沙箱类型关键词（如 "example"、"remote"、"third party"）
     * @return 对应的 CodeSandbox 实例
     */
    public static CodeSandbox newInstance(String type) {
        if (type == null) {
            return new ExampleCodeSandbox();
        }
        switch (type.trim().toLowerCase()) {
            case "example":
                return new ExampleCodeSandbox();
            case "remote":
                return new RemoteCodeSandbox();
            case "third party":
            case "thirdparty":
            case "third_party":
                return new ThirdPartyCodeSandbox();
            default:
                return new ExampleCodeSandbox();
        }
    }
}
