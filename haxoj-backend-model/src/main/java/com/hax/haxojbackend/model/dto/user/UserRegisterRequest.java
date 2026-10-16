/*
 * @Author: hax 3152409286@qq.com
 * @Date: 2025-04-30 11:27:01
 * @LastEditors: hax 3152409286@qq.com
 * @LastEditTime: 2026-05-22 20:56:56
 * @FilePath: /haxoj/haxoj-backend/src/main/java/com/hax/haxojbackend/model/dto/user/UserRegisterRequest.java
 * @Description: 这是默认设置,请设置`customMade`, 打开koroFileHeader查看配置 进行设置: https://github.com/OBKoro1/koro1FileHeader/wiki/%E9%85%8D%E7%BD%AE
 */
package com.hax.haxojbackend.model.dto.user;

import java.io.Serializable;
import lombok.Data;

/**
 * 用户注册请求体
 */
@Data
public class UserRegisterRequest implements Serializable {

    private static final long serialVersionUID = 3191241716373120793L;

    private String userAccount;

    private String userPassword;

    private String checkPassword;

    /**
     * 用户昵称（可选）
     */
    private String userName;
}
