#!/usr/bin/env python3
"""
Generate a realistic LDIF file simulating a mid-sized company (~5000 users).
Uses inetOrgPerson + posixAccount for users, groupOfNames for groups.

Usage: python3 generate-test-ldif.py > test-data.ldif
"""

import hashlib
import random
import string
import sys

random.seed(42)  # reproducible output

BASE_DN = "dc=acmecorp,dc=com"

# ---------------------------------------------------------------------------
# Realistic name pools
# ---------------------------------------------------------------------------
FIRST_NAMES = [
    "James", "Mary", "Robert", "Patricia", "John", "Jennifer", "Michael",
    "Linda", "David", "Elizabeth", "William", "Barbara", "Richard", "Susan",
    "Joseph", "Jessica", "Thomas", "Sarah", "Christopher", "Karen", "Charles",
    "Lisa", "Daniel", "Nancy", "Matthew", "Betty", "Anthony", "Margaret",
    "Mark", "Sandra", "Donald", "Ashley", "Steven", "Kimberly", "Paul",
    "Emily", "Andrew", "Donna", "Joshua", "Michelle", "Kenneth", "Carol",
    "Kevin", "Amanda", "Brian", "Dorothy", "George", "Melissa", "Timothy",
    "Deborah", "Ronald", "Stephanie", "Edward", "Rebecca", "Jason", "Sharon",
    "Jeffrey", "Laura", "Ryan", "Cynthia", "Jacob", "Kathleen", "Gary",
    "Amy", "Nicholas", "Angela", "Eric", "Shirley", "Jonathan", "Anna",
    "Stephen", "Brenda", "Larry", "Pamela", "Justin", "Emma", "Scott",
    "Nicole", "Brandon", "Helen", "Benjamin", "Samantha", "Samuel", "Katherine",
    "Raymond", "Christine", "Gregory", "Debra", "Frank", "Rachel", "Alexander",
    "Carolyn", "Patrick", "Janet", "Jack", "Catherine", "Dennis", "Maria",
    "Jerry", "Heather", "Tyler", "Diane", "Aaron", "Ruth", "Jose", "Julie",
    "Adam", "Olivia", "Nathan", "Joyce", "Henry", "Virginia", "Peter", "Victoria",
    "Zachary", "Kelly", "Douglas", "Lauren", "Harold", "Christina", "Carl",
    "Joan", "Arthur", "Evelyn", "Gerald", "Judith", "Roger", "Megan",
    "Keith", "Andrea", "Jeremy", "Cheryl", "Terry", "Hannah", "Sean", "Jacqueline",
    "Austin", "Martha", "Christian", "Gloria", "Ethan", "Teresa", "Dylan",
    "Ann", "Jesse", "Sara", "Bryan", "Madison", "Albert", "Frances", "Philip",
    "Kathryn", "Wayne", "Janice", "Billy", "Jean", "Eugene", "Abigail",
    "Russell", "Alice", "Vincent", "Judy", "Bobby", "Sophia", "Johnny", "Grace",
    "Gabriel", "Denise", "Logan", "Amber", "Aiden", "Doris", "Connor", "Marilyn",
    "Roy", "Danielle", "Louis", "Beverly", "Randy", "Isabella", "Howard", "Theresa",
    "Victor", "Diana", "Martin", "Natalie", "Elijah", "Brittany", "Caleb",
    "Charlotte", "Mason", "Marie", "Liam", "Kayla", "Noah", "Alexis", "Lucas",
    "Lori", "Isaiah", "Alyssa", "Owen", "Jane", "Adrian", "Mildred",
    "Ravi", "Priya", "Amit", "Deepa", "Sanjay", "Anita", "Rajesh", "Sunita",
    "Wei", "Mei", "Jun", "Xia", "Hao", "Yun", "Chen", "Ling",
    "Hiroshi", "Yuki", "Takeshi", "Akiko", "Kenji", "Sakura", "Yuto", "Hana",
    "Carlos", "Maria", "Miguel", "Ana", "Luis", "Sofia", "Diego", "Carmen",
    "Ahmed", "Fatima", "Omar", "Aisha", "Hassan", "Leila", "Ali", "Noor",
    "Pierre", "Marie", "Jean", "Claire", "Marc", "Sophie", "Laurent", "Camille",
    "Hans", "Anna", "Klaus", "Petra", "Stefan", "Monika", "Juergen", "Sabine",
]

LAST_NAMES = [
    "Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller",
    "Davis", "Rodriguez", "Martinez", "Hernandez", "Lopez", "Gonzalez",
    "Wilson", "Anderson", "Thomas", "Taylor", "Moore", "Jackson", "Martin",
    "Lee", "Perez", "Thompson", "White", "Harris", "Sanchez", "Clark",
    "Ramirez", "Lewis", "Robinson", "Walker", "Young", "Allen", "King",
    "Wright", "Scott", "Torres", "Nguyen", "Hill", "Flores", "Green",
    "Adams", "Nelson", "Baker", "Hall", "Rivera", "Campbell", "Mitchell",
    "Carter", "Roberts", "Gomez", "Phillips", "Evans", "Turner", "Diaz",
    "Parker", "Cruz", "Edwards", "Collins", "Reyes", "Stewart", "Morris",
    "Morales", "Murphy", "Cook", "Rogers", "Gutierrez", "Ortiz", "Morgan",
    "Cooper", "Peterson", "Bailey", "Reed", "Kelly", "Howard", "Ramos",
    "Kim", "Cox", "Ward", "Richardson", "Watson", "Brooks", "Chavez",
    "Wood", "James", "Bennett", "Gray", "Mendoza", "Ruiz", "Hughes",
    "Price", "Alvarez", "Castillo", "Sanders", "Patel", "Myers", "Long",
    "Ross", "Foster", "Jimenez", "Powell", "Jenkins", "Perry", "Russell",
    "Sullivan", "Bell", "Coleman", "Butler", "Henderson", "Barnes", "Gonzales",
    "Fisher", "Vasquez", "Simmons", "Graham", "Murray", "Ford", "Castro",
    "Kumar", "Singh", "Sharma", "Gupta", "Verma", "Joshi", "Chopra", "Mehta",
    "Wang", "Li", "Zhang", "Liu", "Chen", "Yang", "Huang", "Wu",
    "Tanaka", "Sato", "Suzuki", "Takahashi", "Watanabe", "Yamamoto", "Ito",
    "Nakamura", "Mueller", "Schmidt", "Schneider", "Fischer", "Weber", "Meyer",
    "Wagner", "Becker", "Dubois", "Moreau", "Laurent", "Simon", "Bernard",
    "Fontaine", "Roux", "Durand", "Blanc",
]

# ---------------------------------------------------------------------------
# Company structure — departments, titles, group assignments
# ---------------------------------------------------------------------------
DEPARTMENTS = {
    "Engineering": {
        "ou": "Engineering",
        "titles": [
            "Software Engineer", "Senior Software Engineer",
            "Staff Engineer", "Principal Engineer",
            "Engineering Manager", "QA Engineer", "Senior QA Engineer",
            "DevOps Engineer", "Senior DevOps Engineer", "Site Reliability Engineer",
        ],
        "headcount_pct": 0.30,
    },
    "Product": {
        "ou": "Product",
        "titles": [
            "Product Manager", "Senior Product Manager",
            "Director of Product", "Product Analyst",
            "UX Designer", "Senior UX Designer", "UX Researcher",
        ],
        "headcount_pct": 0.08,
    },
    "Sales": {
        "ou": "Sales",
        "titles": [
            "Account Executive", "Senior Account Executive",
            "Sales Development Representative", "Sales Manager",
            "Regional Sales Director", "Solutions Engineer",
        ],
        "headcount_pct": 0.15,
    },
    "Marketing": {
        "ou": "Marketing",
        "titles": [
            "Marketing Specialist", "Senior Marketing Specialist",
            "Content Strategist", "Digital Marketing Manager",
            "Brand Manager", "Marketing Analyst", "Growth Manager",
        ],
        "headcount_pct": 0.07,
    },
    "Finance": {
        "ou": "Finance",
        "titles": [
            "Financial Analyst", "Senior Financial Analyst",
            "Accountant", "Senior Accountant", "Controller",
            "FP&A Manager", "Treasury Analyst",
        ],
        "headcount_pct": 0.06,
    },
    "Human Resources": {
        "ou": "HumanResources",
        "titles": [
            "HR Generalist", "Senior HR Generalist",
            "HR Business Partner", "Recruiter", "Senior Recruiter",
            "Compensation Analyst", "HR Manager",
        ],
        "headcount_pct": 0.05,
    },
    "Legal": {
        "ou": "Legal",
        "titles": [
            "Corporate Counsel", "Senior Counsel", "Paralegal",
            "Compliance Analyst", "Senior Compliance Analyst",
            "Legal Operations Manager",
        ],
        "headcount_pct": 0.03,
    },
    "Customer Support": {
        "ou": "CustomerSupport",
        "titles": [
            "Support Specialist", "Senior Support Specialist",
            "Support Team Lead", "Technical Support Engineer",
            "Customer Success Manager", "Support Manager",
        ],
        "headcount_pct": 0.10,
    },
    "IT": {
        "ou": "IT",
        "titles": [
            "Systems Administrator", "Senior Systems Administrator",
            "Network Engineer", "IT Support Specialist",
            "Security Analyst", "IT Manager", "Database Administrator",
        ],
        "headcount_pct": 0.06,
    },
    "Operations": {
        "ou": "Operations",
        "titles": [
            "Operations Analyst", "Senior Operations Analyst",
            "Operations Manager", "Facilities Coordinator",
            "Procurement Specialist", "Supply Chain Analyst",
        ],
        "headcount_pct": 0.05,
    },
    "Research": {
        "ou": "Research",
        "titles": [
            "Research Scientist", "Senior Research Scientist",
            "Data Scientist", "Senior Data Scientist",
            "Machine Learning Engineer", "Research Director",
        ],
        "headcount_pct": 0.05,
    },
}

LOCATIONS = [
    ("San Francisco", "CA", "US", "+1"),
    ("New York", "NY", "US", "+1"),
    ("Austin", "TX", "US", "+1"),
    ("Chicago", "IL", "US", "+1"),
    ("Seattle", "WA", "US", "+1"),
    ("Denver", "CO", "US", "+1"),
    ("Boston", "MA", "US", "+1"),
    ("London", "", "GB", "+44"),
    ("Berlin", "", "DE", "+49"),
    ("Toronto", "ON", "CA", "+1"),
    ("Sydney", "NSW", "AU", "+61"),
    ("Bangalore", "KA", "IN", "+91"),
    ("Tokyo", "", "JP", "+81"),
    ("Singapore", "", "SG", "+65"),
]

# Cross-functional groups that span departments
CROSS_FUNCTIONAL_GROUPS = [
    ("AllEmployees", "All company employees"),
    ("VPN-Users", "VPN access group"),
    ("Office365-Users", "Microsoft 365 license holders"),
    ("Slack-Users", "Slack workspace members"),
    ("GitHub-Users", "GitHub organization members"),
    ("AWS-ReadOnly", "AWS console read-only access"),
    ("AWS-Developers", "AWS developer access"),
    ("Jira-Users", "Jira project access"),
    ("Confluence-Users", "Confluence wiki access"),
    ("BuildSystem-Users", "CI/CD pipeline access"),
    ("OnCall-Rotation", "On-call rotation members"),
    ("SecurityChampions", "Security champions program"),
    ("DiversityCouncil", "Diversity and inclusion council"),
    ("TechTalks-Organizers", "Tech talks organizing committee"),
    ("MentorshipProgram", "Mentorship program participants"),
    ("EmergencyContacts", "Emergency response team"),
    ("DataAccess-PII", "PII data access authorization"),
    ("DataAccess-Financial", "Financial data access authorization"),
    ("Facilities-BadgeAccess", "Building badge access"),
    ("Parking-Permit", "Parking permit holders"),
]

TOTAL_USERS = 5000

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
def ldif_wrap(value):
    """Return value, base64-encoding if it contains non-ASCII."""
    try:
        value.encode("ascii")
        return value
    except UnicodeEncodeError:
        import base64
        return value  # keep ASCII for simplicity

def phone_number(country_code):
    area = random.randint(200, 999)
    num1 = random.randint(100, 999)
    num2 = random.randint(1000, 9999)
    return f"{country_code} {area}-{num1}-{num2}"

def employee_number(index):
    return f"EMP{index:06d}"

def make_uid(first, last, used_uids):
    """Generate a unique uid from first.last, appending a number if needed."""
    base = f"{first.lower()}.{last.lower()}"
    # sanitize
    base = "".join(c for c in base if c.isalnum() or c == ".")
    uid = base
    counter = 2
    while uid in used_uids:
        uid = f"{base}{counter}"
        counter += 1
    used_uids.add(uid)
    return uid

# ---------------------------------------------------------------------------
# Main generation
# ---------------------------------------------------------------------------
def main():
    out = sys.stdout
    used_uids = set()

    # Assign headcounts per department
    dept_names = list(DEPARTMENTS.keys())
    dept_users = {}
    assigned = 0
    for i, name in enumerate(dept_names):
        if i == len(dept_names) - 1:
            count = TOTAL_USERS - assigned  # remainder goes to last dept
        else:
            count = round(DEPARTMENTS[name]["headcount_pct"] * TOTAL_USERS)
        dept_users[name] = count
        assigned += count

    # ------------------------------------------------------------------
    # 1. Root entry
    # ------------------------------------------------------------------
    out.write(f"""# =============================================================================
# LDIF Test Data — AcmeCorp (~{TOTAL_USERS} users)
# Generated for LDAPPortal testing
# Base DN: {BASE_DN}
# =============================================================================

# Root entry
dn: {BASE_DN}
objectClass: top
objectClass: domain
dc: acmecorp
description: AcmeCorp - Test LDAP Directory

""")

    # ------------------------------------------------------------------
    # 2. Top-level OUs
    # ------------------------------------------------------------------
    for ou_name in ["People", "Groups", "ServiceAccounts"]:
        out.write(f"""dn: ou={ou_name},{BASE_DN}
objectClass: top
objectClass: organizationalUnit
ou: {ou_name}
description: {ou_name} container

""")

    # Department OUs under People
    for dept_name, dept_info in DEPARTMENTS.items():
        ou = dept_info["ou"]
        out.write(f"""dn: ou={ou},ou=People,{BASE_DN}
objectClass: top
objectClass: organizationalUnit
ou: {ou}
description: {dept_name} department

""")

    # Sub-OUs under Groups
    for sub_ou in ["Departments", "Projects", "Access"]:
        out.write(f"""dn: ou={sub_ou},ou=Groups,{BASE_DN}
objectClass: top
objectClass: organizationalUnit
ou: {sub_ou}
description: {sub_ou} groups

""")

    # ------------------------------------------------------------------
    # 3. Generate users
    # ------------------------------------------------------------------
    all_users = []  # list of (uid, dn, dept_name)
    uid_number = 10000

    for dept_name, count in dept_users.items():
        dept_info = DEPARTMENTS[dept_name]
        ou = dept_info["ou"]
        titles = dept_info["titles"]

        for i in range(count):
            first = random.choice(FIRST_NAMES)
            last = random.choice(LAST_NAMES)
            uid = make_uid(first, last, used_uids)
            dn = f"uid={uid},ou={ou},ou=People,{BASE_DN}"
            title = random.choice(titles)
            loc = random.choice(LOCATIONS)
            city, state, country, country_code = loc
            emp_no = employee_number(uid_number - 10000 + 1)
            phone = phone_number(country_code)
            display_name = f"{first} {last}"
            mail = f"{uid}@acmecorp.com"

            if state:
                full_loc = f"{city}, {state}"
                postal = f"{city}, {state}, {country}"
            else:
                full_loc = city
                postal = f"{city}, {country}"

            out.write(f"""dn: {dn}
objectClass: top
objectClass: inetOrgPerson
objectClass: organizationalPerson
objectClass: person
objectClass: posixAccount
uid: {uid}
cn: {display_name}
givenName: {first}
sn: {last}
displayName: {display_name}
mail: {mail}
title: {title}
ou: {dept_info['ou']}
l: {full_loc}
postalAddress: {postal}
telephoneNumber: {phone}
employeeNumber: {emp_no}
employeeType: {"Contractor" if random.random() < 0.08 else "Full-Time"}
uidNumber: {uid_number}
gidNumber: {1000 + list(DEPARTMENTS.keys()).index(dept_name)}
homeDirectory: /home/{uid}
loginShell: /bin/bash
userPassword: {{SSHA}}placeholder

""")

            all_users.append((uid, dn, dept_name))
            uid_number += 1

    # ------------------------------------------------------------------
    # 4. Department groups
    # ------------------------------------------------------------------
    dept_members = {}
    for uid, dn, dept_name in all_users:
        dept_members.setdefault(dept_name, []).append(dn)

    for dept_name, dept_info in DEPARTMENTS.items():
        ou = dept_info["ou"]
        members = dept_members.get(dept_name, [])
        if not members:
            continue
        group_cn = f"dept-{ou.lower()}"
        out.write(f"""dn: cn={group_cn},ou=Departments,ou=Groups,{BASE_DN}
objectClass: top
objectClass: groupOfNames
cn: {group_cn}
description: {dept_name} department group
""")
        for m in members:
            out.write(f"member: {m}\n")
        out.write("\n")

    # ------------------------------------------------------------------
    # 5. Department manager groups (subset)
    # ------------------------------------------------------------------
    for dept_name, dept_info in DEPARTMENTS.items():
        ou = dept_info["ou"]
        members = dept_members.get(dept_name, [])
        # ~5% of department are managers
        mgr_count = max(2, len(members) // 20)
        mgr_members = random.sample(members, min(mgr_count, len(members)))
        group_cn = f"mgr-{ou.lower()}"
        out.write(f"""dn: cn={group_cn},ou=Departments,ou=Groups,{BASE_DN}
objectClass: top
objectClass: groupOfNames
cn: {group_cn}
description: {dept_name} managers
""")
        for m in mgr_members:
            out.write(f"member: {m}\n")
        out.write("\n")

    # ------------------------------------------------------------------
    # 6. Project / team groups (Engineering sub-teams, etc.)
    # ------------------------------------------------------------------
    eng_members = dept_members.get("Engineering", [])
    project_groups = [
        ("project-atlas", "Project Atlas — Core platform team"),
        ("project-beacon", "Project Beacon — Mobile app team"),
        ("project-cipher", "Project Cipher — Security tooling team"),
        ("project-delta", "Project Delta — Data pipeline team"),
        ("project-echo", "Project Echo — API platform team"),
        ("project-forge", "Project Forge — Developer experience team"),
        ("project-gateway", "Project Gateway — Integration services team"),
        ("project-horizon", "Project Horizon — ML/AI platform team"),
        ("project-iris", "Project Iris — Analytics dashboard team"),
        ("project-jade", "Project Jade — Infrastructure team"),
    ]
    # distribute engineering members across projects (some overlap)
    random.shuffle(eng_members)
    chunk = len(eng_members) // len(project_groups)
    for idx, (cn, desc) in enumerate(project_groups):
        start = idx * chunk
        # allow ~20% overlap with next group
        end = min(start + chunk + chunk // 5, len(eng_members))
        members = eng_members[start:end]
        if not members:
            continue
        out.write(f"""dn: cn={cn},ou=Projects,ou=Groups,{BASE_DN}
objectClass: top
objectClass: groupOfNames
cn: {cn}
description: {desc}
""")
        for m in members:
            out.write(f"member: {m}\n")
        out.write("\n")

    # ------------------------------------------------------------------
    # 7. Cross-functional / access groups
    # ------------------------------------------------------------------
    all_dns = [dn for _, dn, _ in all_users]

    for cn, desc in CROSS_FUNCTIONAL_GROUPS:
        if cn == "AllEmployees":
            members = all_dns
        elif cn in ("VPN-Users", "Office365-Users", "Slack-Users",
                     "Confluence-Users", "Facilities-BadgeAccess"):
            # ~90-95% of all users
            members = random.sample(all_dns, int(len(all_dns) * random.uniform(0.90, 0.95)))
        elif cn in ("GitHub-Users", "BuildSystem-Users", "Jira-Users"):
            # engineering + product + IT + research
            members = []
            for uid, dn, dept in all_users:
                if dept in ("Engineering", "Product", "IT", "Research"):
                    members.append(dn)
            # plus a random 10% of others
            others = [dn for _, dn, d in all_users if d not in ("Engineering", "Product", "IT", "Research")]
            members += random.sample(others, len(others) // 10)
        elif cn in ("AWS-ReadOnly",):
            members = random.sample(all_dns, int(len(all_dns) * 0.30))
        elif cn in ("AWS-Developers",):
            eng_it = [dn for _, dn, d in all_users if d in ("Engineering", "IT", "Research")]
            members = random.sample(eng_it, int(len(eng_it) * 0.70))
        elif cn in ("OnCall-Rotation",):
            eng_it = [dn for _, dn, d in all_users if d in ("Engineering", "IT", "Customer Support")]
            members = random.sample(eng_it, int(len(eng_it) * 0.25))
        elif cn in ("DataAccess-PII",):
            members = [dn for _, dn, d in all_users if d in ("Human Resources", "Legal")]
            members += random.sample(all_dns, 50)
            members = list(set(members))
        elif cn in ("DataAccess-Financial",):
            members = [dn for _, dn, d in all_users if d in ("Finance",)]
            members += random.sample(all_dns, 30)
            members = list(set(members))
        elif cn == "Parking-Permit":
            members = random.sample(all_dns, int(len(all_dns) * 0.40))
        else:
            # small groups: 20-80 members
            members = random.sample(all_dns, random.randint(20, 80))

        out.write(f"""dn: cn={cn},ou=Access,ou=Groups,{BASE_DN}
objectClass: top
objectClass: groupOfNames
cn: {cn}
description: {desc}
""")
        for m in members:
            out.write(f"member: {m}\n")
        out.write("\n")

    # ------------------------------------------------------------------
    # 8. Service accounts
    # ------------------------------------------------------------------
    service_accounts = [
        ("svc-ldapadmin", "LDAPPortal application service account"),
        ("svc-jenkins", "Jenkins CI/CD service account"),
        ("svc-monitoring", "Monitoring system service account"),
        ("svc-backup", "Backup system service account"),
        ("svc-mailrelay", "Mail relay service account"),
        ("svc-sso", "SSO gateway service account"),
        ("svc-audit", "Audit logging service account"),
        ("svc-provisioning", "User provisioning service account"),
        ("svc-api-gateway", "API gateway service account"),
        ("svc-vault", "Secrets management service account"),
    ]
    for uid, desc in service_accounts:
        out.write(f"""dn: uid={uid},ou=ServiceAccounts,{BASE_DN}
objectClass: top
objectClass: inetOrgPerson
objectClass: organizationalPerson
objectClass: person
uid: {uid}
cn: {desc}
sn: ServiceAccount
description: {desc}
userPassword: {{SSHA}}placeholder

""")

    # Service accounts group
    out.write(f"""dn: cn=ServiceAccounts,ou=Access,ou=Groups,{BASE_DN}
objectClass: top
objectClass: groupOfNames
cn: ServiceAccounts
description: All service accounts
""")
    for uid, _ in service_accounts:
        out.write(f"member: uid={uid},ou=ServiceAccounts,{BASE_DN}\n")
    out.write("\n")

    # Summary to stderr
    total_groups = (
        len(DEPARTMENTS) +          # dept groups
        len(DEPARTMENTS) +          # manager groups
        len(project_groups) +       # project groups
        len(CROSS_FUNCTIONAL_GROUPS) +  # access groups
        1                           # service accounts group
    )
    print(f"# Generated {len(all_users)} users, {total_groups} groups, "
          f"{len(service_accounts)} service accounts", file=sys.stderr)
    print(f"# Base DN: {BASE_DN}", file=sys.stderr)


if __name__ == "__main__":
    main()
