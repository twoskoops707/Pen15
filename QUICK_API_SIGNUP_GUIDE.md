# QUICK API SIGNUP GUIDE - Free Tiers

## 🚀 FASTEST SIGNUPS (Google OAuth - 1 Click)

These services let you sign up with your Google account - no forms to fill:

### 1. GitHub Personal Access Token ⭐ ESSENTIAL
**Link:** https://github.com/settings/tokens

**Steps:**
1. Sign in with Google (if not already)
2. Click "Generate new token (classic)"
3. Select scopes: `repo`, `read:org`, `read:user`
4. Click "Generate token"
5. **Copy token immediately** (shown only once)

**Usage:** Code search, repository intelligence, OSINT

---

### 2. VirusTotal ⭐ ESSENTIAL
**Link:** https://www.virustotal.com/gui/join-us

**Steps:**
1. Click "Sign up with Google"
2. Accept permissions
3. Go to https://www.virustotal.com/gui/user/YOUR_USERNAME/apikey
4. Copy API key

**Free Tier:** 4 requests/minute, 500/day
**Usage:** File/URL scanning, malware detection

---

### 3. IPInfo
**Link:** https://ipinfo.io/signup

**Steps:**
1. Click "Sign up with Google"
2. Confirm email
3. Dashboard shows API token automatically

**Free Tier:** 50k requests/month
**Usage:** IP geolocation, ASN lookup

---

### 4. Hunter.io
**Link:** https://hunter.io/users/sign_up

**Steps:**
1. Click "Sign up with Google"
2. Verify email
3. Go to https://hunter.io/api_keys
4. Copy API key

**Free Tier:** 25 searches/month, 50 verifications/month
**Usage:** Email finder, email verification

---

## 📋 SIMPLE SIGNUPS (Email + Password - 2 Minutes Each)

### 5. Shodan ⭐ ESSENTIAL
**Link:** https://account.shodan.io/register

**Steps:**
1. Enter email (or use Google)
2. Create password
3. Verify email
4. Go to https://account.shodan.io/
5. Copy API key from dashboard

**Free Tier:** 100 queries/month
**Usage:** Device search, exploit search, network monitoring
**Note:** Paid upgrade ($59/lifetime) for unlimited - OPTIONAL

---

### 6. AlienVault OTX
**Link:** https://otx.alienvault.com/accounts/signup/

**Steps:**
1. Sign up with email
2. Verify email
3. Go to https://otx.alienvault.com/api
4. Copy OTX Key

**Free Tier:** Unlimited
**Usage:** Threat intelligence, IOCs

---

### 7. SecurityTrails
**Link:** https://securitytrails.com/app/signup

**Steps:**
1. Sign up with email
2. Verify email
3. Go to https://securitytrails.com/app/account/credentials
4. Copy API key

**Free Tier:** 50 queries/month
**Usage:** DNS history, subdomain discovery

---

### 8. HaveIBeenPwned
**Link:** https://haveibeenpwned.com/API/Key

**Steps:**
1. Click "Get API Key"
2. Enter email
3. Pay $3.50/month OR use free breach check (no API)

**Note:** API requires paid subscription - use web interface for free
**Free Alternative:** Use https://haveibeenpwned.com/ web interface directly

---

### 9. Censys
**Link:** https://censys.io/register

**Steps:**
1. Sign up with email
2. Verify email
3. Go to https://censys.io/account/api
4. Copy API ID and Secret

**Free Tier:** 250 queries/month
**Usage:** Internet scanning, certificate search

---

### 10. BinaryEdge
**Link:** https://app.binaryedge.io/sign-up

**Steps:**
1. Sign up with email
2. Verify email
3. Go to https://app.binaryedge.io/account/api
4. Copy API key

**Free Tier:** 250 queries/month
**Usage:** Internet scanning, threat intelligence

---

### 11. GreyNoise
**Link:** https://viz.greynoise.io/signup

**Steps:**
1. Sign up with email
2. Verify email
3. Go to https://viz.greynoise.io/account
4. Copy API key

**Free Tier:** 500 queries/month
**Usage:** Internet noise detection, scanner identification

---

### 12. IPQualityScore
**Link:** https://www.ipqualityscore.com/create-account

**Steps:**
1. Sign up with email
2. Verify email
3. Dashboard shows API key

**Free Tier:** 5,000 lookups/month
**Usage:** Fraud detection, IP reputation

---

## 🔍 SEARCH ENGINE APIs

### 13. Google Custom Search
**Link:** https://programmablesearchengine.google.com/controlpanel/create

**Steps:**
1. Sign in with Google
2. Create new search engine
3. Add "Search the entire web"
4. Get Search Engine ID
5. Get API key from https://console.cloud.google.com/apis/credentials

**Free Tier:** 100 queries/day
**Usage:** Google dorking, web search

---

### 14. Bing Search API
**Link:** https://www.microsoft.com/en-us/bing/apis/bing-web-search-api

**Steps:**
1. Sign in with Microsoft account
2. Click "Try now"
3. Create Azure account (free)
4. Create Bing Search resource
5. Copy API key

**Free Tier:** 1,000 queries/month
**Usage:** Web search alternative

---

## 🐦 SOCIAL MEDIA APIs (OPTIONAL - Rate Limited)

### 15. Twitter API
**Link:** https://developer.twitter.com/en/portal/petition/essential/basic-info

**Steps:**
1. Apply for Essential access (free)
2. Describe use case: "Security research and OSINT"
3. Wait for approval (usually instant)
4. Create app
5. Copy API keys

**Free Tier:** 500k tweets/month (read)
**Usage:** Social media intelligence

**Note:** Approval required - may take 1-2 days

---

## 💰 PAID ONLY (Skip These)

### ❌ ZoomEye - Requires payment
### ❌ Onyphe - Requires payment
### ❌ FullContact - Free tier very limited
### ❌ Facebook Graph API - Requires app review
### ❌ LinkedIn API - Requires company verification

---

## 📝 QUICK SIGNUP CHECKLIST

**Priority 1 - Essential (Do These First):**
- [ ] GitHub Personal Access Token
- [ ] VirusTotal
- [ ] Shodan
- [ ] AlienVault OTX

**Priority 2 - Highly Useful:**
- [ ] IPInfo
- [ ] Hunter.io
- [ ] SecurityTrails
- [ ] Censys

**Priority 3 - Nice to Have:**
- [ ] BinaryEdge
- [ ] GreyNoise
- [ ] IPQualityScore
- [ ] Google Custom Search

**Priority 4 - Optional:**
- [ ] Twitter API (requires approval)
- [ ] Bing Search API

---

## 💾 SAVING YOUR API KEYS

### Method 1: In App (Coming Soon)
1. Open app
2. Settings → API Keys
3. Enter keys
4. Save

### Method 2: In API_CREDENTIALS.md
1. Edit `API_CREDENTIALS.md`
2. Replace "(Add your key here)" with actual keys
3. **DO NOT commit to Git!**

### Method 3: Environment Variables
```bash
# Add to ~/.bashrc or ~/.zshrc
export SHODAN_API_KEY="your_key_here"
export VIRUSTOTAL_API_KEY="your_key_here"
export GITHUB_TOKEN="your_token_here"
# ... etc
```

---

## 🎯 ESTIMATED TIME

- **Priority 1 (4 services):** ~15 minutes total
- **Priority 2 (4 services):** ~20 minutes total
- **Priority 3 (4 services):** ~20 minutes total
- **Total for all 12:** ~55 minutes

**Strategy:**
1. Open all signup links in separate tabs
2. Use Google OAuth where available (instant)
3. For email signups, use same password for all
4. Verify emails in bulk
5. Copy all API keys to notepad
6. Add to API_CREDENTIALS.md at once

---

## 🔐 SECURITY TIPS

1. **Use unique password** for API accounts (if not using Google OAuth)
2. **Enable 2FA** where available
3. **Never share API keys publicly**
4. **Rotate keys monthly**
5. **Monitor usage** to detect unauthorized use
6. **Revoke keys** immediately if compromised

---

## 📊 EXPECTED CAPABILITIES AFTER SETUP

With all Priority 1 + Priority 2 services (8 APIs):

✅ **Network Intelligence:**
- Shodan: 100 device searches/month
- Censys: 250 internet scans/month

✅ **Threat Intelligence:**
- VirusTotal: 500 malware scans/day
- AlienVault: Unlimited IOC lookups

✅ **Domain Intelligence:**
- SecurityTrails: 50 DNS history queries/month
- IPInfo: 50k IP lookups/month

✅ **People Intelligence:**
- Hunter.io: 25 email searches/month

✅ **Code Intelligence:**
- GitHub: 5,000 API calls/hour

This covers 90% of OSINT needs for pentesting!

---

## 🆘 TROUBLESHOOTING

**"Email not verified"**
→ Check spam folder, wait 5 minutes, request new verification

**"API key not working"**
→ Check rate limits, verify key copied correctly, check API documentation

**"Access denied"**
→ Some services require approval - wait 24-48 hours or use alternative

**"Payment required"**
→ You hit the free tier limit - upgrade or use alternative service

---

## 🚀 READY TO START?

1. **Bookmark this file** for reference
2. **Start with Priority 1** (most important)
3. **Work through Priority 2** when you have time
4. **Skip paid services** - free tiers are sufficient
5. **Come back and report** which services you got keys for

**Once you have keys, I'll help you configure the app!**
