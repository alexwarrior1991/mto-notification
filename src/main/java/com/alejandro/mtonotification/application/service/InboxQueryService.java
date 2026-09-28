package com.alejandro.mtonotification.application.service;

import com.alejandro.mtonotification.application.dto.common.PageResponse;
import com.alejandro.mtonotification.application.dto.inbox.InboxFilter;
import com.alejandro.mtonotification.application.dto.inbox.InboxItemResponse;
import com.alejandro.mtonotification.application.dto.inbox.ReadAllResponse;
import com.alejandro.mtonotification.application.dto.inbox.UnreadCountResponse;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/** Mi bandeja: lo dirigido a alguna de las claves de mi token, con leida o no leida por persona. */
public interface InboxQueryService {

    PageResponse<InboxItemResponse> myInbox(InboxFilter filter, Pageable pageable);

    UnreadCountResponse unreadCount();

    /** 404 {@code NTF-404} si no es mia: el id no dice si existe. */
    InboxItemResponse markRead(UUID notificationId);

    ReadAllResponse markAllRead();
}
