// SYNTHETIC_PHONE_FIXTURES_ONLY: phone-shaped values below are generated test fixtures.
package com.yuelin.controller;

import com.yuelin.dto.ProfileUpdateDTO;
import com.yuelin.dto.Result;
import com.yuelin.service.UploadAssetService;
import com.yuelin.dto.UserDTO;
import com.yuelin.entity.User;
import com.yuelin.entity.UserInfo;
import com.yuelin.service.IUserInfoService;
import com.yuelin.service.IUserService;
import com.yuelin.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserControllerTest {

    private UserController controller;
    private IUserService userService;
    private IUserInfoService userInfoService;

    @BeforeEach
    void setUp() {
        controller = new UserController();
        ReflectionTestUtils.setField(controller, "uploadAssetService", mock(UploadAssetService.class));
        userService = mock(IUserService.class);
        userInfoService = mock(IUserInfoService.class);
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "userInfoService", userInfoService);
        ReflectionTestUtils.setField(controller, "stringRedisTemplate", mock(StringRedisTemplate.class));
    }

    @AfterEach
    void clearUser() {
        UserHolder.removeUser();
    }

    @Test
    void publicInfoExposesOnlyCityAndIntroduction() {
        UserInfo profile = new UserInfo()
                .setUserId(2L)
                .setCity("杭州")
                .setIntroduce("热爱社区生活")
                .setGender(true)
                .setBirthday(LocalDate.of(2000, 1, 2))
                .setCredits(99)
                .setCreateTime(LocalDateTime.now());
        when(userInfoService.getById(2L)).thenReturn(profile);

        Result result = controller.publicInfo(2L);

        assertTrue(result.getSuccess());
        Map<?, ?> data = assertInstanceOf(Map.class, result.getData());
        assertEquals(Set.of("city", "introduce"), data.keySet());
        assertEquals("杭州", data.get("city"));
        assertEquals("热爱社区生活", data.get("introduce"));
    }

    @Test
    void fullInfoRejectsAnotherUserBeforeQueryingStorage() {
        UserDTO current = new UserDTO();
        current.setId(1L);
        UserHolder.saveUser(current);

        Result result = controller.info(2L);

        assertFalse(result.getSuccess());
        assertEquals("无权查看他人的完整资料", result.getErrorMsg());
        verifyNoInteractions(userInfoService);
    }

    @Test
    void fullInfoAllowsOwnerAndRemovesAuditTimestamps() {
        UserDTO current = new UserDTO();
        current.setId(2L);
        UserHolder.saveUser(current);
        UserInfo profile = new UserInfo()
                .setUserId(2L)
                .setCity("杭州")
                .setCreateTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now());
        when(userInfoService.getById(2L)).thenReturn(profile);

        Result result = controller.info(2L);

        assertTrue(result.getSuccess());
        UserInfo data = assertInstanceOf(UserInfo.class, result.getData());
        assertNull(data.getCreateTime());
        assertNull(data.getUpdateTime());
    }

    @Test
    void profileUpdateFailsBeforeWritingDetailsWhenUserRowWasNotUpdated() {
        UserDTO current = new UserDTO();
        current.setId(2L);
        UserHolder.saveUser(current);
        ProfileUpdateDTO form = new ProfileUpdateDTO();
        form.setNickName("新昵称");
        when(userService.updateById(any(User.class))).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> controller.updateProfile(form, null));
        verifyNoInteractions(userInfoService);
    }

    @Test
    void publicUserUsesSafeUserDtoWithoutPhoneOrPassword() {
        User user = new User()
                .setId(2L)
                .setPhone("13800000000")
                .setPassword("secret")
                .setNickName("邻里伙伴")
                .setIcon("/imgs/icons/default-icon.svg");
        when(userService.getById(2L)).thenReturn(user);

        Result result = controller.publicUser(2L);

        assertTrue(result.getSuccess());
        UserDTO data = assertInstanceOf(UserDTO.class, result.getData());
        assertEquals(2L, data.getId());
        assertEquals("邻里伙伴", data.getNickName());
        assertEquals("/imgs/icons/default-icon.svg", data.getIcon());
        assertEquals(3, UserDTO.class.getDeclaredFields().length);
    }
}
