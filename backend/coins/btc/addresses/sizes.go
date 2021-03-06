// Copyright 2018 Shift Devices AG
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package addresses

import "github.com/digitalbitbox/bitbox-wallet-app/backend/signing"

// SigScriptWitnessSize returns the maximum possible sigscript size for a given address type.
func SigScriptWitnessSize(configuration *signing.Configuration) (int, bool) {
	if configuration.Multisig() {
		// OP_N (1 byte, signingThreshold)
		// numberOfSigners*(
		// OP_DATA_33
		// 33 bytes of compressed pubkey
		// )
		// OP_N (1 byte, numberOfSigners) OP_CHECKMULTISIG (1 byte)
		redeemScriptSize := 1 + configuration.NumberOfSigners()*(1+33) + 1 + 1
		// OP_0 (1 byte)
		// numSigs*(
		// OP_DATA_72
		// 72 bytes of signature data (including SIGHASH op)
		// ) <redeemScript>

		// The redeemScript is prefixed with its length. If its length is below OP_PUSHDATA1 (76),
		// 1 byte is used. If it's below 0xff, 2 bytes are needed. For longer multisig
		// redeemScripts, 3 bytes are needed.
		redeemScriptLenSize := 1
		if redeemScriptSize >= 76 {
			redeemScriptLenSize = 2
		}
		if redeemScriptSize >= 0xff {
			redeemScriptLenSize = 3
		}
		return 1 + configuration.SigningThreshold()*(1+72) + redeemScriptLenSize + redeemScriptSize, false
	}
	switch configuration.ScriptType() {
	case signing.ScriptTypeP2PKH:
		// OP_DATA_72
		// 72 bytes of signature data (including SIGHASH op)
		// OP_DATA_33
		// 33 bytes of compressed pubkey
		// OP_73, OP_33 are data push ops.
		return 1 + 72 + 1 + 33, false
	case signing.ScriptTypeP2WPKHP2SH:
		// OP_0 (1 byte) OP_20 (1 byte) pubkeyHash (20 bytes)
		const redeemScriptSize = 1 + 1 + 20
		// OP_DATA_22 (1 Byte) redeemScript (22 bytes)
		return 1 + redeemScriptSize, true
	case signing.ScriptTypeP2WPKH:
		return 0, true // hooray
	default:
		panic("unknown address type")
	}
}
