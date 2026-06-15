// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Decodes Active Directory's {@code userAccountControl} bitmask into
 * human-readable status flags.
 */
public final class UserAccountControlDecoder {

    private UserAccountControlDecoder() {}

    public static final int SCRIPT                         = 0x0001;
    public static final int ACCOUNTDISABLE                 = 0x0002;
    public static final int HOMEDIR_REQUIRED               = 0x0008;
    public static final int LOCKOUT                        = 0x0010;
    public static final int PASSWD_NOTREQD                 = 0x0020;
    public static final int PASSWD_CANT_CHANGE             = 0x0040;
    public static final int ENCRYPTED_TEXT_PWD_ALLOWED     = 0x0080;
    public static final int NORMAL_ACCOUNT                 = 0x0200;
    public static final int INTERDOMAIN_TRUST_ACCOUNT      = 0x0800;
    public static final int WORKSTATION_TRUST_ACCOUNT      = 0x1000;
    public static final int SERVER_TRUST_ACCOUNT           = 0x2000;
    public static final int DONT_EXPIRE_PASSWD             = 0x10000;
    public static final int MNS_LOGON_ACCOUNT             = 0x20000;
    public static final int SMARTCARD_REQUIRED             = 0x40000;
    public static final int TRUSTED_FOR_DELEGATION         = 0x80000;
    public static final int NOT_DELEGATED                  = 0x100000;
    public static final int USE_DES_KEY_ONLY               = 0x200000;
    public static final int DONT_REQ_PREAUTH               = 0x400000;
    public static final int PASSWORD_EXPIRED               = 0x800000;
    public static final int TRUSTED_TO_AUTH_FOR_DELEGATION = 0x1000000;
    public static final int PARTIAL_SECRETS_ACCOUNT        = 0x4000000;

    /**
     * Decodes the UAC value into a list of human-readable flag names.
     */
    public static List<String> decode(int uac) {
        List<String> flags = new ArrayList<>();
        if ((uac & ACCOUNTDISABLE) != 0)             flags.add("DISABLED");
        if ((uac & LOCKOUT) != 0)                    flags.add("LOCKED_OUT");
        if ((uac & PASSWD_NOTREQD) != 0)             flags.add("PASSWORD_NOT_REQUIRED");
        if ((uac & PASSWD_CANT_CHANGE) != 0)         flags.add("PASSWORD_CANT_CHANGE");
        if ((uac & NORMAL_ACCOUNT) != 0)             flags.add("NORMAL_ACCOUNT");
        if ((uac & DONT_EXPIRE_PASSWD) != 0)         flags.add("PASSWORD_NEVER_EXPIRES");
        if ((uac & PASSWORD_EXPIRED) != 0)           flags.add("PASSWORD_EXPIRED");
        if ((uac & SMARTCARD_REQUIRED) != 0)         flags.add("SMARTCARD_REQUIRED");
        if ((uac & TRUSTED_FOR_DELEGATION) != 0)     flags.add("TRUSTED_FOR_DELEGATION");
        if ((uac & NOT_DELEGATED) != 0)              flags.add("NOT_DELEGATED");
        if ((uac & INTERDOMAIN_TRUST_ACCOUNT) != 0)  flags.add("INTERDOMAIN_TRUST");
        if ((uac & WORKSTATION_TRUST_ACCOUNT) != 0)  flags.add("WORKSTATION_TRUST");
        if ((uac & SERVER_TRUST_ACCOUNT) != 0)       flags.add("SERVER_TRUST");
        return flags;
    }

    /**
     * Returns true if the UAC indicates the account is disabled.
     */
    public static boolean isDisabled(int uac) {
        return (uac & ACCOUNTDISABLE) != 0;
    }

    /**
     * Returns true if the UAC indicates the account is locked out.
     */
    public static boolean isLockedOut(int uac) {
        return (uac & LOCKOUT) != 0;
    }
}
