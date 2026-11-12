package com.carddemo.users;

import java.io.Serializable;

/** CDEMO-CU00-INFO: the page COUSR00C shows, kept between key presses. */
public record UserListPage(String firstId, String lastId, int pageNumber, boolean hasNext) implements Serializable {

    static final UserListPage FIRST = new UserListPage("", "", 1, false);
}
