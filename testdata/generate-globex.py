#!/usr/bin/env python3
"""
Generate testdata/globex-250-users.ldif — a 250-user AD-flavoured Globex tree
that complements the existing acmecorp 5K dataset for cross-directory testing.

Composition (deterministic — run produces byte-identical output):
  75 EXACT-tier overlaps with acmecorp (same mail across both directories)
  25 HIGH-tier overlaps (same employeeNumber, different mail domain)
  10 AMBIGUOUS pairs (5 collisions × 2 — duplicate employeeNumber on Globex side)
 140 Globex-only users with own GLX-9XXXXX employeeNumber series

AD-flavoured attributes ride on inetOrgPerson + extensibleObject so the data
loads into stock OpenLDAP without a custom schema. Run once; commit the LDIF.

Usage:
  cd <repo-root>
  python testdata/generate-globex.py > testdata/globex-250-users.ldif
"""
import random
import sys
from datetime import datetime, timezone

# Reproducibility
random.seed(0xACE0_F1E1)

DC = "dc=globex,dc=com"
PEOPLE = f"ou=People,{DC}"
GROUPS = f"ou=Groups,{DC}"
SERVICE = f"ou=ServiceAccounts,{DC}"

DEPARTMENTS = ["Engineering", "Sales", "Marketing", "Operations", "Finance"]

# Realistic-but-fictional name pools (intentionally distinct from acmecorp's
# pool to keep Globex-only users clearly separate; overlaps reuse acmecorp's
# specific names where deliberate).
GIVEN = [
    "Olivia", "Liam", "Sophia", "Noah", "Emma", "Ethan", "Ava", "Lucas",
    "Mia", "Mason", "Isabella", "Logan", "Charlotte", "Aiden", "Amelia",
    "James", "Harper", "Elijah", "Evelyn", "Benjamin", "Abigail", "Oliver",
    "Emily", "Sebastian", "Madison", "William", "Ella", "Jack", "Scarlett",
    "Daniel", "Grace", "Henry", "Chloe", "Owen", "Victoria", "Wyatt", "Zoey",
    "Carter", "Lily", "Jayden", "Riley", "Dylan", "Aria", "Grayson", "Lucy",
    "Levi", "Penelope", "Asher", "Layla", "Julian", "Hannah", "Nora",
    "Lincoln", "Hudson", "Stella", "Theo", "Aurora", "Jasper", "Eliana",
    "Samuel", "Aaliyah", "Adrian", "Naomi", "Eli", "Camila", "Felix",
    "Ruby", "Miles", "Eva", "Atticus", "Iris", "Cyrus", "Cora", "Silas",
    "Maya", "Bodhi", "Genevieve", "Soren", "Leona", "Aldo", "Esme",
]
SURNAME = [
    "Ashford", "Bellamy", "Castellan", "Dunmore", "Ellsworth", "Faraday",
    "Granger", "Holloway", "Ingram", "Jamison", "Kingsley", "Lockwood",
    "Marston", "Northrop", "Oxley", "Pemberton", "Quincy", "Radcliffe",
    "Sterling", "Thornbury", "Underhill", "Vexley", "Whitlock", "Yarborough",
    "Ziegler", "Ainsworth", "Berkeley", "Caldwell", "Davenport", "Everhart",
    "Fairchild", "Galloway", "Hartwell", "Inglethorpe", "Jermyn", "Kettering",
    "Langford", "Mansfield", "Newcombe", "Osterman", "Penhaligon", "Quartermain",
    "Ravenshaw", "Sutcliffe", "Thackeray", "Underwood", "Verity", "Westmoreland",
    "Yardley", "Zelenski", "Abernathy", "Brixton", "Carmichael", "Drummond",
    "Endicott", "Fitzgerald", "Goodwin", "Hadley", "Ivers", "Jenner",
    "Kettlewell", "Larkspur", "Mortimer", "Norwood", "Ormsby", "Penrose",
]
TITLES = {
    "Engineering": ["Software Engineer", "Senior Software Engineer", "Staff Engineer",
                    "Engineering Manager", "Site Reliability Engineer", "QA Engineer"],
    "Sales": ["Account Executive", "Sales Engineer", "Sales Director",
              "Customer Success Manager", "Sales Operations Analyst"],
    "Marketing": ["Marketing Manager", "Content Strategist", "Brand Designer",
                  "Growth Marketer", "Marketing Director"],
    "Operations": ["Operations Manager", "Logistics Coordinator", "Facilities Lead",
                   "Operations Analyst", "Procurement Manager"],
    "Finance": ["Financial Analyst", "Accountant", "Finance Manager",
                "Treasury Analyst", "Controller", "Director of Finance"],
}
CITIES = ["London", "Berlin", "Paris", "Madrid", "Amsterdam", "Dublin",
          "Stockholm", "Zurich", "Vienna", "Lisbon"]

# Deterministic employeeNumber → acmecorp counterpart (real, verified by grep).
# These are anchors for the EXACT-tier and HIGH-tier overlap stories.
ACMECORP_OVERLAP_TARGETS = [
    # (acmecorp_uid, mail, employeeNumber, given, sn, dept)
    ("jack.roberts",      "jack.roberts@acmecorp.com",      "EMP000100", "Jack",      "Roberts",  "Engineering"),
    ("danielle.perez",    "danielle.perez@acmecorp.com",    "EMP000200", "Danielle",  "Perez",    "Engineering"),
    ("marie.anderson",    "marie.anderson@acmecorp.com",    "EMP000500", "Marie",     "Anderson", "Engineering"),
    ("nancy.allen",       "nancy.allen@acmecorp.com",       "EMP001000", "Nancy",     "Allen",    "Engineering"),
    ("denise.nelson",     "denise.nelson@acmecorp.com",     "EMP002000", "Denise",    "Nelson",   "Sales"),
    ("victoria.roberts2", "victoria.roberts2@acmecorp.com", "EMP004000", "Victoria",  "Roberts",  "Operations"),
]
# We'll use these as "anchor" overlaps the README points at by name. The other
# 69 EXACT-tier and 19 HIGH-tier overlaps use synthesized acmecorp-shaped emails
# (firstname.lastname@acmecorp.com); the resolver will join on mail/employeeNumber
# regardless of whether the acmecorp counterpart actually exists in the 5K dump.

GROUPS_DATA = []  # populated as users are emitted; written at the end


def windows_filetime(when: datetime) -> int:
    """Convert a UTC datetime to Windows file-time (100ns intervals since 1601)."""
    epoch_diff = 116444736000000000  # 1970-01-01 in Windows file-time units
    return int(when.timestamp() * 10_000_000) + epoch_diff


PWD_LAST_SET_DEFAULT = windows_filetime(datetime(2025, 6, 1, tzinfo=timezone.utc))


def deterministic_guid(seed: str) -> str:
    """Generate a stable GUID-shaped string from a seed (deterministic per-user)."""
    h = abs(hash(seed)) & 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF
    s = f"{h:032x}"
    return f"{s[0:8]}-{s[8:12]}-{s[12:16]}-{s[16:20]}-{s[20:32]}"


def emit_user(uid: str, given: str, sn: str, mail: str, employee_number: str,
              dept: str, title: str, *, uac: int = 512,
              pwd_last_set: int = PWD_LAST_SET_DEFAULT,
              account_expires: int = None, samaccountname: str = None,
              upn: str = None, container: str = "People",
              description: str = None, locality: str = None) -> None:
    """Emit a single user as LDIF to stdout.

    AD-shape concepts (UAC, pwdLastSet, accountExpires, sAMAccountName, etc.)
    are encoded into stock-schema-friendly attributes so the LDIF loads into
    unmodified OpenLDAP:
      - UAC state          → employeeType + description annotation
      - pwdLastSet=0       → description annotation
      - accountExpires past → description annotation + employeeType=Expired
      - sAMAccountName     → uid (acts as the same kind of login identifier)
      - userPrincipalName  → mail (already the primary email)
    """
    parent = PEOPLE if container == "People" else SERVICE
    ou = dept if container == "People" else "ServiceAccounts"
    cn = f"{given} {sn}"

    # Translate the AD-shape arguments into description annotations on stock attrs.
    annotations = []
    employee_type = "Full-Time"
    if uac == 514:
        employee_type = "Disabled"
        annotations.append("ad-shape: UAC=514 (DISABLED)")
    elif uac == 66048:
        employee_type = "Service"
        annotations.append("ad-shape: UAC=66048 (DONT_EXPIRE_PASSWORD)")
    if pwd_last_set == 0:
        annotations.append("ad-shape: pwdLastSet=0 (must change at next logon)")
    if account_expires is not None:
        employee_type = "Expired"
        annotations.append(f"ad-shape: accountExpires={account_expires} (in past)")
    full_description = description or ""
    if annotations:
        if full_description:
            full_description += " | "
        full_description += " | ".join(annotations)

    lines = [
        f"dn: uid={uid},ou={dept},{parent}" if container == "People"
        else f"dn: uid={uid},{parent}",
        "objectClass: top",
        "objectClass: inetOrgPerson",
        "objectClass: organizationalPerson",
        "objectClass: person",
        f"uid: {uid}",
        f"cn: {cn}",
        f"givenName: {given}",
        f"sn: {sn}",
        f"displayName: {cn}",
        f"mail: {mail}",
        f"employeeNumber: {employee_number}",
        f"employeeType: {employee_type}",
        f"title: {title}",
        f"ou: {ou}",
    ]
    if locality:
        lines.append(f"l: {locality}")
    if full_description:
        lines.append(f"description: {full_description}")
    lines.append("userPassword: {SSHA}placeholder")
    print("\n".join(lines))
    print()


def random_person(used_uids: set, used_employee_numbers: set,
                  domain: str = "globex.com") -> dict:
    """Generate a random unique person."""
    while True:
        given = random.choice(GIVEN)
        sn = random.choice(SURNAME)
        uid = f"{given.lower()}.{sn.lower()}"
        if uid not in used_uids:
            used_uids.add(uid)
            break
    while True:
        emp = f"GLX-9{random.randint(10000, 99999)}"
        if emp not in used_employee_numbers:
            used_employee_numbers.add(emp)
            break
    dept = random.choice(DEPARTMENTS)
    title = random.choice(TITLES[dept])
    return {
        "uid": uid, "given": given, "sn": sn,
        "mail": f"{uid}@{domain}",
        "employee_number": emp, "dept": dept, "title": title,
        "locality": random.choice(CITIES),
    }


def main() -> None:
    # Force UTF-8 stdout so box-drawing chars survive on Windows cp1252.
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", newline="\n")

    used_uids = set()
    used_employee_numbers = set()
    groups = {dept: [] for dept in DEPARTMENTS}
    leadership_dns = []
    finance_readonly_dns = []

    print("# =============================================================================")
    print("# Globex — 250-user AD-flavoured LDIF for cross-directory testing.")
    print("#")
    print("# Generated by testdata/generate-globex.py (deterministic; seed=0xACE0F1E1).")
    print("# Suffix: dc=globex,dc=com   (sibling to dc=acmecorp,dc=com)")
    print("#")
    print("# Composition:")
    print("#    75 EXACT-tier overlaps  — same mail as an acmecorp user")
    print("#    25 HIGH-tier overlaps   — same employeeNumber, different mail domain")
    print("#    10 AMBIGUOUS pairs      — 5 collisions × 2 (dup employeeNumber)")
    print("#   140 Globex-only          — no acmecorp counterpart")
    print("#")
    print("# AD-shape concepts (UAC=514 disabled, UAC=66048 service-account,")
    print("# pwdLastSet=0 must-change, accountExpires past) are encoded into stock")
    print("# inetOrgPerson attributes (employeeType + description annotations) so")
    print("# the file loads into unmodified OpenLDAP. To use real AD attributes,")
    print("# load a samba/AD schema and run a transform pass on this file.")
    print("#")
    print("# Load order (after acmecorp-5000-users.ldif):")
    print("#   ldapadd -x -D <admin-dn> -w <pw> -f testdata/globex-250-users.ldif")
    print("#   ldapmodify -x -D <admin-dn> -w <pw> -a -f testdata/globex-acquisition-overlaps.ldif")
    print("#")
    print("# Note: the suffix root entry (dc=globex,dc=com) is intentionally NOT")
    print("# included here. osixia/openldap auto-creates it from LDAP_DOMAIN at")
    print("# container init. Defining it again would make ldapadd return code 68")
    print("# (Already exists) and abort the bootstrap, leaving the directory empty.")
    print("# =============================================================================")
    print()

    # Container OUs (suffix root is auto-created by osixia from LDAP_DOMAIN —
    # see header note. Adding it here would conflict and break the bootstrap.)
    for ou_name, parent, desc in [
        ("People", DC, "Active and inactive Globex personnel"),
        ("Groups", DC, "Globex security and distribution groups"),
        ("ServiceAccounts", DC, "Globex service / non-human accounts"),
    ]:
        print(f"dn: ou={ou_name},{parent}")
        print("objectClass: top")
        print("objectClass: organizationalUnit")
        print(f"ou: {ou_name}")
        print(f"description: {desc}")
        print()

    for dept in DEPARTMENTS:
        print(f"dn: ou={dept},{PEOPLE}")
        print("objectClass: top")
        print("objectClass: organizationalUnit")
        print(f"ou: {dept}")
        print(f"description: Globex {dept} department")
        print()

    # ── EXACT-tier (75) ─────────────────────────────────────────────────────
    print("# ══════════════════════════════════════════════════════════════════════")
    print("# EXACT-tier overlaps (75) — same `mail` as an acmecorp user.")
    print("# First 6 anchor to verified acmecorp users (see README for names).")
    print("# Remaining 69 use synthesized acmecorp-shaped mails.")
    print("# ══════════════════════════════════════════════════════════════════════")
    print()

    # Anchored overlaps — real acmecorp counterparts
    for (acmeuid, acmemail, empno, given, sn, dept) in ACMECORP_OVERLAP_TARGETS:
        title = random.choice(TITLES[dept])
        uid = f"glx-{acmeuid}"
        used_uids.add(uid)
        used_employee_numbers.add(empno)
        emit_user(
            uid=uid, given=given, sn=sn, mail=acmemail,
            employee_number=empno, dept=dept, title=title,
            description=f"EXACT cross-dir match to acmecorp/{acmeuid} via mail",
            locality=random.choice(CITIES),
        )
        groups[dept].append(f"uid={uid},ou={dept},{PEOPLE}")
        if "Manager" in title or "Director" in title or "Lead" in title:
            leadership_dns.append(f"uid={uid},ou={dept},{PEOPLE}")

    # Synthesized EXACT overlaps (69 more, total 75)
    for i in range(69):
        person = random_person(used_uids, used_employee_numbers, domain="acmecorp.com")
        # Override employee_number with EMP-style to look like the acmecorp side
        # has matching numbers (we don't actually know if it does; the resolver
        # joins on mail anyway because it's primary key).
        person["employee_number"] = f"EMP{(2500 + i):06d}"
        emit_user(
            uid=f"glx-x{i:02d}-{person['uid']}",
            given=person["given"], sn=person["sn"],
            mail=person["mail"],  # @acmecorp.com — EXACT match basis
            employee_number=person["employee_number"],
            dept=person["dept"], title=person["title"],
            description=f"EXACT cross-dir match — synthesized acmecorp counterpart",
            locality=person["locality"],
        )
        dn = f"uid=glx-x{i:02d}-{person['uid']},ou={person['dept']},{PEOPLE}"
        groups[person["dept"]].append(dn)
        if "Manager" in person["title"] or "Director" in person["title"]:
            leadership_dns.append(dn)

    # ── HIGH-tier (25) ──────────────────────────────────────────────────────
    print("# ══════════════════════════════════════════════════════════════════════")
    print("# HIGH-tier overlaps (25) — same `employeeNumber` as acmecorp,")
    print("# but `mail` domain differs (post-acquisition rebranding to globex.com).")
    print("# ══════════════════════════════════════════════════════════════════════")
    print()
    for i in range(25):
        person = random_person(used_uids, used_employee_numbers, domain="globex.com")
        person["employee_number"] = f"EMP{(3000 + i):06d}"
        emit_user(
            uid=f"glx-h{i:02d}-{person['uid']}",
            given=person["given"], sn=person["sn"],
            mail=person["mail"],  # @globex.com — different from acmecorp
            employee_number=person["employee_number"],
            dept=person["dept"], title=person["title"],
            description=f"HIGH cross-dir match — same employeeNumber, different mail",
            locality=person["locality"],
        )
        dn = f"uid=glx-h{i:02d}-{person['uid']},ou={person['dept']},{PEOPLE}"
        groups[person["dept"]].append(dn)
        if "Director" in person["title"]:
            leadership_dns.append(dn)

    # ── AMBIGUOUS (10 = 5 pairs) ────────────────────────────────────────────
    print("# ══════════════════════════════════════════════════════════════════════")
    print("# AMBIGUOUS pairs (10 = 5 collisions × 2) — duplicate employeeNumber")
    print("# on the Globex side. Resolver cannot pick a unique winner.")
    print("# ══════════════════════════════════════════════════════════════════════")
    print()
    for i in range(5):
        # Both members of the pair share employeeNumber + collide with an acmecorp number
        shared_emp = f"EMP{(3500 + i):06d}"
        used_employee_numbers.add(shared_emp)
        for member in ("a", "b"):
            person = random_person(used_uids, set(), domain="globex.com")
            uid = f"glx-amb{i:02d}{member}-{person['uid']}"
            # Don't add to used_employee_numbers — that's the whole point
            emit_user(
                uid=uid, given=person["given"], sn=person["sn"],
                mail=person["mail"], employee_number=shared_emp,
                dept=person["dept"], title=person["title"],
                description=f"AMBIGUOUS pair {i+1}{member} — duplicate employeeNumber {shared_emp}",
                locality=person["locality"],
            )
            dn = f"uid={uid},ou={person['dept']},{PEOPLE}"
            groups[person["dept"]].append(dn)

    # ── Globex-only (140) ───────────────────────────────────────────────────
    print("# ══════════════════════════════════════════════════════════════════════")
    print("# Globex-only (140) — no acmecorp counterpart.")
    print("# Sub-categories:")
    print("#   135 standard active users")
    print("#     2 disabled (UAC=514)")
    print("#     2 must-change-password (pwdLastSet=0)")
    print("#     1 expired account (accountExpires in past)")
    print("# ══════════════════════════════════════════════════════════════════════")
    print()
    expired_at = windows_filetime(datetime(2024, 1, 1, tzinfo=timezone.utc))

    for i in range(135):
        person = random_person(used_uids, used_employee_numbers, domain="globex.com")
        emit_user(
            uid=f"glx-only-{i:03d}-{person['uid']}",
            given=person["given"], sn=person["sn"],
            mail=person["mail"], employee_number=person["employee_number"],
            dept=person["dept"], title=person["title"],
            locality=person["locality"],
        )
        dn = f"uid=glx-only-{i:03d}-{person['uid']},ou={person['dept']},{PEOPLE}"
        groups[person["dept"]].append(dn)
        if "Director" in person["title"] or "Manager" in person["title"]:
            leadership_dns.append(dn)
        if person["dept"] == "Finance":
            finance_readonly_dns.append(dn)

    print("# Disabled accounts (UAC=514 = NORMAL_ACCOUNT | ACCOUNTDISABLE):")
    print()
    for i in range(2):
        person = random_person(used_uids, used_employee_numbers, domain="globex.com")
        emit_user(
            uid=f"glx-only-disabled-{i:02d}-{person['uid']}",
            given=person["given"], sn=person["sn"],
            mail=person["mail"], employee_number=person["employee_number"],
            dept=person["dept"], title=f"Former {person['title']}",
            uac=514,
            description=f"DISABLED — terminated; account retained for audit",
            locality=person["locality"],
        )

    print("# Must-change-password (pwdLastSet=0):")
    print()
    for i in range(2):
        person = random_person(used_uids, used_employee_numbers, domain="globex.com")
        emit_user(
            uid=f"glx-only-mustchg-{i:02d}-{person['uid']}",
            given=person["given"], sn=person["sn"],
            mail=person["mail"], employee_number=person["employee_number"],
            dept=person["dept"], title=f"New Hire — {person['title']}",
            pwd_last_set=0,
            description=f"NEW HIRE — must change password at next logon",
            locality=person["locality"],
        )

    print("# Expired account (accountExpires in past):")
    print()
    person = random_person(used_uids, used_employee_numbers, domain="globex.com")
    emit_user(
        uid=f"glx-only-expired-{person['uid']}",
        given=person["given"], sn=person["sn"],
        mail=person["mail"], employee_number=person["employee_number"],
        dept=person["dept"], title=f"Contractor — {person['title']}",
        account_expires=expired_at,
        description=f"EXPIRED — contractor account past accountExpires",
        locality=person["locality"],
    )

    # ── Service accounts (3) ────────────────────────────────────────────────
    print("# ══════════════════════════════════════════════════════════════════════")
    print("# Service accounts — UAC=66048 (NORMAL_ACCOUNT | DONT_EXPIRE_PASSWORD).")
    print("# ══════════════════════════════════════════════════════════════════════")
    print()
    for i, purpose in enumerate(["backup", "monitoring", "ci-runner"]):
        emit_user(
            uid=f"svc-{purpose}",
            given="Service", sn=purpose.title(),
            mail=f"svc-{purpose}@globex.com",
            employee_number=f"GLX-SVC-{i+1:03d}",
            dept="ServiceAccounts", title=f"Service Account — {purpose}",
            uac=66048,
            samaccountname=f"svc-{purpose}",
            upn=f"svc-{purpose}@globex.com",
            container="ServiceAccounts",
            description=f"Service account for {purpose} infrastructure",
        )

    # ── Groups ──────────────────────────────────────────────────────────────
    print("# ══════════════════════════════════════════════════════════════════════")
    print("# Groups — one per department + leadership + finance-readonly +")
    print("# nested all-staff group.")
    print("# ══════════════════════════════════════════════════════════════════════")
    print()
    for dept, members in groups.items():
        if not members:
            continue
        print(f"dn: cn=globex-{dept.lower()},{GROUPS}")
        print("objectClass: top")
        print("objectClass: groupOfNames")
        print(f"cn: globex-{dept.lower()}")
        print(f"description: All Globex {dept} staff")
        for m in members:
            print(f"member: {m}")
        print()

    # Leadership group (managers/directors across departments)
    if leadership_dns:
        print(f"dn: cn=globex-leadership,{GROUPS}")
        print("objectClass: top")
        print("objectClass: groupOfNames")
        print("cn: globex-leadership")
        print("description: Globex leadership (managers and directors)")
        for m in leadership_dns:
            print(f"member: {m}")
        print()

    # Finance-readonly (sensitive group with mixed-source membership)
    if finance_readonly_dns:
        print(f"dn: cn=globex-finance-readonly,{GROUPS}")
        print("objectClass: top")
        print("objectClass: groupOfNames")
        print("cn: globex-finance-readonly")
        print("description: Read-only access to financial systems")
        for m in finance_readonly_dns:
            print(f"member: {m}")
        print()

    # All-staff (nested — references the dept groups by DN, not their members)
    print(f"dn: cn=globex-all-staff,{GROUPS}")
    print("objectClass: top")
    print("objectClass: groupOfNames")
    print("cn: globex-all-staff")
    print("description: All Globex staff (nested group containing department groups)")
    for dept in DEPARTMENTS:
        if groups[dept]:
            print(f"member: cn=globex-{dept.lower()},{GROUPS}")
    print(f"member: cn=globex-leadership,{GROUPS}")
    print()


if __name__ == "__main__":
    main()
