# API CREDENTIALS & KEYS

## OSINT & Reconnaissance APIs

### Network Intelligence
- **Shodan** - https://account.shodan.io/
  - API Key: (Add your key here)
  - Usage: Device search, exploit search, network monitoring

- **Censys** - https://censys.io/
  - API ID: (Add your ID here)
  - API Secret: (Add your secret here)
  - Usage: Internet-wide scanning, certificate search

- **BinaryEdge** - https://www.binaryedge.io/
  - API Key: (Add your key here)
  - Usage: Internet scanning, threat intelligence

- **ZoomEye** - https://www.zoomeye.org/
  - API Key: (Add your key here)
  - Usage: Cyberspace search, device discovery

- **Onyphe** - https://www.onyphe.io/
  - API Key: (Add your key here)
  - Usage: Threat intelligence, IP information

- **GreyNoise** - https://www.greynoise.io/
  - API Key: (Add your key here)
  - Usage: Internet noise detection, mass scanner identification

### Threat Intelligence
- **VirusTotal** - https://www.virustotal.com/
  - API Key: (Add your key here)
  - Usage: File/URL scanning, malware detection

- **AlienVault OTX** - https://otx.alienvault.com/
  - API Key: (Add your key here)
  - Usage: Threat data, indicators of compromise

- **IPQualityScore** - https://www.ipqualityscore.com/
  - API Key: (Add your key here)
  - Usage: Fraud detection, IP reputation

### Domain & DNS
- **SecurityTrails** - https://securitytrails.com/
  - API Key: (Add your key here)
  - Usage: DNS history, subdomain discovery

- **IPInfo** - https://ipinfo.io/
  - API Key: (Add your key here)
  - Usage: IP geolocation, ASN lookup

### People & Email
- **Hunter.io** - https://hunter.io/
  - API Key: (Add your key here)
  - Usage: Email finder, email verification

- **FullContact** - https://www.fullcontact.com/
  - API Key: (Add your key here)
  - Usage: Person/company enrichment

- **HaveIBeenPwned** - https://haveibeenpwned.com/API/Key
  - API Key: (Add your key here)
  - Usage: Breach database lookup

### Social Media
- **GitHub** - https://github.com/settings/tokens
  - Personal Access Token: (Add your token here)
  - Usage: Code search, repository intelligence

- **Twitter API** - https://developer.twitter.com/
  - API Key: (Add your key here)
  - API Secret: (Add your secret here)
  - Bearer Token: (Add your token here)
  - Usage: Social media intelligence

### Search Engines
- **Google Custom Search** - https://developers.google.com/custom-search
  - API Key: (Add your key here)
  - Search Engine ID: (Add your ID here)
  - Usage: Web search, dorking

- **Bing Search** - https://www.microsoft.com/en-us/bing/apis/bing-web-search-api
  - API Key: (Add your key here)
  - Usage: Web search alternative

## How to Add Keys to App

### Method 1: Via Settings Activity
1. Open app
2. Click settings icon (top right)
3. Enter your API keys
4. Click Save

### Method 2: Via API Keys Manager (Coming Soon)
1. Open app
2. Navigate to OSINT Scanner
3. Click "Manage API Keys"
4. Enter keys for each service
5. Auto-export to SpiderFoot & Recon-NG configs

### Method 3: Manual Configuration

**For SpiderFoot:**
```bash
# Edit SpiderFoot config
nano ~/.spiderfoot/spiderfoot.db

# Or export from app
python3 -m spiderfoot -s 0.0.0.0:5001
```

**For Recon-NG:**
```bash
# Open Recon-NG
recon-ng

# Add keys
[recon-ng][default] > keys add shodan_api YOUR_KEY_HERE
[recon-ng][default] > keys add virustotal_api YOUR_KEY_HERE
# ... etc for all keys
```

## API Rate Limits

| Service | Free Tier Limit | Notes |
|---------|----------------|-------|
| Shodan | 100/month | Paid: Unlimited |
| VirusTotal | 4/minute | Paid: Higher limits |
| Hunter.io | 25/month | Paid: 500-100k/month |
| HaveIBeenPwned | Rate limited | Requires subscription for API |
| Censys | 250/month | Paid: Custom limits |
| GitHub | 60/hour (unauth), 5000/hour (auth) | Use token |

## Security Best Practices

1. **Never commit API keys to Git**
   - Keys in this file are templates only
   - Add API_CREDENTIALS.md to .gitignore

2. **Use environment variables**
   ```bash
   export SHODAN_API_KEY="your_key_here"
   export VIRUSTOTAL_API_KEY="your_key_here"
   ```

3. **Rotate keys regularly**
   - Monthly rotation recommended
   - Immediately rotate if compromised

4. **Monitor usage**
   - Track API calls to avoid rate limits
   - Set up alerts for unusual activity

5. **Use separate keys for testing**
   - Don't use production keys in dev
   - Revoke test keys when done

## Getting Free API Keys

Most services offer free tiers:

1. **Shodan** - Free 100 queries/month with account
2. **VirusTotal** - Free with registration
3. **GitHub** - Free tokens with account
4. **Hunter.io** - 25 free searches/month
5. **IPInfo** - 50k requests/month free

## Integration Status

- ✅ ParameterDialog auto-discovery system
- ✅ Online wordlist URLs (no local storage)
- ⏳ API Keys Manager UI (coming soon)
- ⏳ Auto-export to tool configs (coming soon)
- ⏳ Unified OSINT Scanner UI (coming soon)

## Support

If you need help with API integration:
1. Check tool documentation
2. Verify API key is valid
3. Check rate limits
4. Review error messages in Termux
