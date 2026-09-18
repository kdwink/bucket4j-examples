package com.redshiftsoft.example.webapp.model;

public record User(long userId,
                   String username,
                   String passwordHash,
                   String firstName,
                   String lastName,
                   long organizationId) {

}
