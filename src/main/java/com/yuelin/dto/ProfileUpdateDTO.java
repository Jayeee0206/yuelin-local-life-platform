package com.yuelin.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class ProfileUpdateDTO {
    private String nickName;
    private String icon;
    private String city;
    private String introduce;
    private Boolean gender;
    private LocalDate birthday;
}
