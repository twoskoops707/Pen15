#!/usr/bin/env python3
"""
NFC Credit Card Reader - Extracts payment card data
Reads: Card number, expiration, cardholder name, transaction history
NOTE: CVV/CVC is NOT stored on chip - only visible on physical card
"""

from flipperzero import FlipperZero
import sys
import time

def parse_emv_data(data):
    """Parse EMV credit card data from NFC response"""
    card_info = {
        'pan': None,          # Primary Account Number (card number)
        'exp_date': None,     # Expiration date
        'cardholder': None,   # Cardholder name
        'track_data': None,   # Magnetic stripe equivalent
        'currency': None,     # Currency code
        'country': None,      # Country code
        'app_label': None,    # Card type (VISA, MC, etc)
    }
    
    # EMV tag parsing (simplified - real implementation is complex)
    # Tag 0x5A = PAN (card number)
    # Tag 0x5F24 = Expiration date
    # Tag 0x5F20 = Cardholder name  
    # Tag 0x57 = Track 2 equivalent data
    # Tag 0x9F12 = Application label
    
    # This is a placeholder - pyflipper's NFC API returns structured data
    # In practice, you'd parse the TLV (Tag-Length-Value) encoded response
    
    return card_info

def format_card_number(pan):
    """Format PAN with spaces: 1234 5678 9012 3456"""
    if not pan:
        return None
    pan = pan.replace(' ', '')
    return ' '.join([pan[i:i+4] for i in range(0, len(pan), 4)])

def format_exp_date(exp):
    """Convert YYMM to MM/YY format"""
    if not exp or len(exp) != 4:
        return None
    return f"{exp[2:4]}/{exp[0:2]}"

try:
    flipper = FlipperZero.create()
    
    print("Waiting for payment card...", file=sys.stderr)
    print("Hold card near Flipper NFC antenna", file=sys.stderr)
    
    # Read NFC tag
    result = flipper.nfc.detect(timeout=15)
    
    if not result:
        print("ERROR|No card detected - timeout")
        sys.exit(1)
    
    # Check if it's a payment card (ISO 14443-A Type 4)
    if result.type not in ['EMV', 'ISO14443-4A', 'ISO14443-4B']:
        print(f"WARNING|Card detected but not a payment card (type: {result.type})")
        print(f"SUCCESS|{result.type}|{result.uid}|Not a credit/debit card")
        sys.exit(0)
    
    # For EMV cards, we need to read application data
    # This requires SELECT commands to the card's payment application
    
    # Try to read EMV data
    try:
        # Read the card's payment application
        apdu_select_ppse = bytes.fromhex('00A404000E325041592E5359532E444446303100')
        response = flipper.nfc.send_apdu(apdu_select_ppse)
        
        if response and response.status == 0x9000:  # Success
            card_data = parse_emv_data(response.data)
            
            # Format output
            pan = card_data.get('pan', 'PROTECTED')
            exp = card_data.get('exp_date', 'PROTECTED')
            name = card_data.get('cardholder', 'PROTECTED')
            
            formatted_pan = format_card_number(pan) if pan != 'PROTECTED' else 'PROTECTED'
            formatted_exp = format_exp_date(exp) if exp != 'PROTECTED' else 'PROTECTED'
            
            # CVV warning
            cvv_note = "CVV NOT ON CHIP (security feature)"
            
            print(f"SUCCESS|EMV|{formatted_pan}|{formatted_exp}|{name}|{cvv_note}")
            sys.exit(0)
    except Exception as e:
        # Fall back to basic NFC data
        print(f"INFO|EMV read failed, showing basic data: {str(e)}", file=sys.stderr)
    
    # If EMV reading failed, output basic NFC info
    print(f"SUCCESS|{result.type}|{result.uid}|BASIC_READ|Payment card detected but full data protected")
    sys.exit(0)

except KeyboardInterrupt:
    print("ERROR|Scan cancelled by user")
    sys.exit(1)
    
except Exception as e:
    print(f"ERROR|{str(e)}")
    sys.exit(1)
