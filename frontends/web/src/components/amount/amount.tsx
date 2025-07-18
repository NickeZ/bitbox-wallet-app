/**
 * Copyright 2023-2025 Shift Crypto AG
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { useContext } from 'react';
import type { CoinUnit, ConversionUnit } from '@/api/account';
import { AppContext } from '@/contexts/AppContext';
import { LocalizationContext } from '@/contexts/localization-context';
import { useMediaQuery } from '@/hooks/mediaquery';
import style from './amount.module.css';

const formatSats = (amount: string): JSX.Element => {
  const blocks: JSX.Element[] = [];
  const blockSize = 3;

  for (let i = amount.length; i > 0 ; i -= blockSize) {
    const start = Math.max(0, i - blockSize);

    blocks.push(
      <span
        key={'block_' + blocks.length}
        className={start === 0 ? '' : style.space}>
        {amount.slice(start, i)}
      </span>
    );
  }

  return (
    <span data-testid="amountBlocks">
      {blocks.reverse()}
    </span>
  );
};

const formatLocalizedAmount = (
  amount: string,
  group: string,
  decimal: string
) => {
  return (
    amount
      .replace('.', '_') // convert decimal first, in case group separator uses dot
      .replace(/[']/g, group) // replace group separator
      .replace('_', decimal)
  );
};

const formatBtc = (
  amount: string,
  group: string,
  decimal: string
) => {
  const dot = amount.indexOf('.');
  if (dot === -1) {
    return amount;
  }
  // localize the first part, everything up to the second decimal place, the rest is grouped by spaces
  const formattedPart = formatLocalizedAmount(amount.slice(0, dot + 3), group, decimal);
  return (
    <span data-testid="amountBlocks">
      <span>
        {formattedPart}
      </span>
      <span className={style.space}>
        {amount.slice(dot + 3, dot + 6)}
      </span>
      <span className={style.space}>
        {amount.slice(dot + 6, dot + 9)}
      </span>
    </span>
  );
};

type TProps = {
  amount: string;
  unit: CoinUnit | ConversionUnit;
  alwaysShowAmounts?: boolean;
  onMobileClick?: () => Promise<void>;
};

export const Amount = ({
  amount,
  unit,
  alwaysShowAmounts = false,
  onMobileClick,
}: TProps) => {
  const isMobile = useMediaQuery('(max-width: 768px)');

  const handleClick = () => {
    if (isMobile && onMobileClick) {
      onMobileClick();
    }
  };

  return (
    <span className={style.amount} onClick={handleClick}>
      <FormattedAmount
        amount={amount}
        unit={unit}
        alwaysShowAmounts={alwaysShowAmounts}
      />
    </span>
  );
};

export const FormattedAmount = ({
  amount,
  unit,
  alwaysShowAmounts = false,
}: Omit<TProps, 'allowRotateCurrencyOnMobile'>) => {
  const { hideAmounts } = useContext(AppContext);
  const { decimal, group } = useContext(LocalizationContext);

  if (!amount) {
    return '---';
  }

  if (hideAmounts && !alwaysShowAmounts) {
    return '***';
  }

  switch (unit) {
  case 'BTC':
  case 'TBTC':
  case 'LTC':
  case 'TLTC':
    return formatBtc(amount, group, decimal);
  case 'sat':
  case 'tsat':
    return formatSats(amount);
  }

  return formatLocalizedAmount(amount, group, decimal);
};
