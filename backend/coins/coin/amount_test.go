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

package coin_test

import (
	"fmt"
	"math/big"
	"testing"
	"testing/quick"

	"github.com/digitalbitbox/bitbox-wallet-app/backend/coins/coin"
	"github.com/stretchr/testify/require"
)

func TestNewAmountFromString(t *testing.T) {
	for decimals := 0; decimals <= 20; decimals++ {
		unit := new(big.Int).Exp(big.NewInt(10), big.NewInt(int64(decimals)), nil)
		t.Run(fmt.Sprintf("decimals=%d", decimals), func(t *testing.T) {
			require.NoError(t, quick.Check(func(amount int64) bool {
				formatted := new(big.Rat).SetFrac(big.NewInt(amount), unit).FloatString(decimals)
				parsedAmount, err := coin.NewAmountFromString(formatted, unit)
				require.NoError(t, err)
				return parsedAmount.Int64() == amount
			}, nil))
		})
	}
	for _, fail := range []string{
		"",
		"1.2 not a number",
		"1/1000",
		"0.123456789", // only up to 8 decimals allowed
	} {
		t.Run(fail, func(t *testing.T) {
			_, err := coin.NewAmountFromString(fail, big.NewInt(1e8))
			require.Error(t, err)
		})
	}
	// parse 2^78
	veryBig, err := coin.NewAmountFromString("3022314549036572.93676544", big.NewInt(1e8))
	require.NoError(t, err)
	require.Equal(t,
		new(big.Int).Exp(big.NewInt(2), big.NewInt(78), nil),
		veryBig.Int(),
	)
}

func TestAmountCopy(t *testing.T) {
	amount := coin.NewAmountFromInt64(1)
	require.Equal(t, big.NewInt(1), amount.Int())
	// Modify copy, check that original does not change.
	amount.Int().SetInt64(2)
	require.Equal(t, big.NewInt(1), amount.Int())
}