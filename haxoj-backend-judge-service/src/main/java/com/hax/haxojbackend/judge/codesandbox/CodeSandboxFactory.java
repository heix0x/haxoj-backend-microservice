package com.hax.haxojbackend.judge.codesandbox;

import com.hax.haxojbackend.judge.codesandbox.impl.ExampleCodeSandbox;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 代码沙箱工厂。
 * <p>
 * 根据传入的关键词返回对应的 {@link CodeSandbox} 实例，关键词来自配置
 * {@code codesandbox.type}（见 application.yml）。
 *
 * <p>支持的关键词（不区分大小写、空格与下划线等价）：
 * <ul>
 * <li><b>example</b> → {@link ExampleCodeSandbox}（示例沙箱，仅做演示）</li>
 * <li><b>remote</b> → 远程沙箱，调用独立部署的 {@code haxoj-code-sandbox} 服务</li>
 * <li><b>third party</b> / <b>thirdparty</b> / <b>third_party</b> → 第三方沙箱</li>
 * </ul>
 * 传入 null、空串或无法识别的关键词时，回落 {@code example}。
 *
 * <h2>为什么从 {@code switch-new} 改成「Spring bean + 查表」</h2>
 * <p>原来这里 {@code new} 出实现类，而 {@code RemoteCodeSandbox} 的沙箱地址与密钥
 * 需要 {@code @Value} 注入 —— {@code @Value} 只对 Spring 容器创建的对象生效，
 * 自己 new 出来的实例拿不到值（拿到的是 null）。所以让所有实现类变成 bean，
 * 由 Spring 把「bean 名 → 实例」这张 Map 直接注入进来，工厂只负责按名字查表。</p>
 *
 * <p>好处：以后新增一种沙箱，只需要写一个 {@code @Component("xxx")} 的实现类，
 * 工厂一行都不用改（原来是每加一种就要改 switch）。</p>
 */
@Slf4j
@Component
public class CodeSandboxFactory {

    /**
     * 无法识别类型时的兜底。
     */
    private static final String DEFAULT_TYPE = "example";

    private final Map<String, CodeSandbox> sandboxes = new HashMap<>();

    /**
     * Spring 会把所有 {@link CodeSandbox} 类型的 bean 收集成 Map 注入，
     * key 是 bean 名（{@code @Component} 里写的那个），value 是实例。
     *
     * @param beans bean 名到实例的映射，由 Spring 容器注入
     */
    public CodeSandboxFactory(Map<String, CodeSandbox> beans) {
        // 统一 key 的形态：小写、去掉空格与下划线。
        // 这样配置里写 "third party"、"third_party"、"thirdParty" 都能命中同一个 bean。
        beans.forEach((name, sandbox) -> sandboxes.put(normalize(name), sandbox));
    }

    /**
     * 根据关键词创建沙箱实例。
     *
     * @param type 沙箱类型关键词（如 "example"、"remote"、"third party"）；null 或无法识别时返回示例沙箱
     * @return 对应的 {@link CodeSandbox} 实例
     */
    public CodeSandbox newInstance(String type) {
        String key = normalize(type);
        CodeSandbox sandbox = sandboxes.get(key);
        if (sandbox == null) {
            log.warn("未识别的沙箱类型「{}」，回落到 {}。已注册的沙箱：{}", type, DEFAULT_TYPE, sandboxes.keySet());
            return sandboxes.get(DEFAULT_TYPE);
        }
        return sandbox;
    }

    /**
     * 归一化：小写 + 去掉空格与下划线 + 去首尾空白。
     */
    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "");
    }
}
