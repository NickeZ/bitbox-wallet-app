/**
 * Copyright 2018 Shift Devices AG
 * Copyright 2023-2024 Shift Crypto AG
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

import { useCallback, useEffect, Fragment } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useSync } from './hooks/api';
import { useDefault } from './hooks/default';
import { usePrevious } from './hooks/previous';
import { useIgnoreDrop } from './hooks/drop';
import { usePlatformClass } from './hooks/platform';
import { AppRouter } from './routes/router';
import { Wizard as BitBox02Wizard } from './routes/device/bitbox02/wizard';
import { getAccounts } from './api/account';
import { syncAccountsList } from './api/accountsync';
import { getDeviceList } from './api/devices';
import { syncDeviceList } from './api/devicessync';
import { syncNewTxs } from './api/transactions';
import { notifyUser } from './api/system';
import { ConnectedApp } from './connected';
import { Alert } from './components/alert/Alert';
import { Aopp } from './components/aopp/aopp';
import { Confirm } from './components/confirm/Confirm';
import { KeystoreConnectPrompt } from './components/keystoreconnectprompt';
import { Sidebar } from './components/sidebar/sidebar';
import { RouterWatcher } from './utils/route';
import { Darkmode } from './components/darkmode/darkmode';
import { AuthRequired } from './components/auth/authrequired';
import { WCSigningRequest } from './components/wallet-connect/incoming-signing-request';
import { Providers } from './contexts/providers';
import { BottomNavigation } from './components/bottom-navigation/bottom-navigation';
import { GlobalBanners } from '@/components/banners';
import styles from './app.module.css';

export const App = () => {
  usePlatformClass();
  const { t } = useTranslation();
  const navigate = useNavigate();
  useIgnoreDrop();

  const accounts = useDefault(useSync(getAccounts, syncAccountsList), []);
  const devices = useDefault(useSync(getDeviceList, syncDeviceList), {});

  const prevDevices = usePrevious(devices);

  useEffect(() => {
    return syncNewTxs((meta) => {
      notifyUser(t('notification.newTxs', {
        count: meta.count,
        accountName: meta.accountName,
      }));
    });
  }, [t]);

  const maybeRoute = useCallback(() => {
    const currentURL = window.location.hash.replace(/^#/, '');
    const isIndex = currentURL === '' || currentURL === '/';
    const inAccounts = currentURL.startsWith('/account/');
    const deviceIDs = Object.keys(devices);

    // QT and Android start their apps in '/index.html' and '/android_asset/web/index.html' respectively
    // This re-routes them to '/' so we have a simpler uri structure
    if (isIndex && currentURL !== '/' && (!accounts || accounts.length === 0)) {
      navigate('/');
      return;
    }
    // if no accounts are registered on specified views route to /
    if (accounts.length === 0 && (
      currentURL.startsWith('/account-summary')
      || currentURL.startsWith('/add-account')
      || currentURL.startsWith('/settings/manage-accounts')
      || currentURL.startsWith('/accounts/')
      // Workaround on mobile where the bottom menu is not shown when there are no devices/accounts.
      // If one is on "More" and the bottom menu disappears, one is stuck.
      || currentURL === '/settings/more'
    )) {
      navigate('/');
      return;
    }
    // if no devices are registered on specified views route to /
    if (
      deviceIDs.length === 0
      && (
        currentURL.startsWith('/settings/device-settings/')
        || currentURL.startsWith('/manage-backups/')
      )
    ) {
      navigate('/');
      return;
    }
    // if device is connected route to device settings
    if (
      deviceIDs.length === 1
      && currentURL === '/settings/no-device-connected'
    ) {
      navigate(`/settings/device-settings/${deviceIDs[0]}`);
      return;
    }
    // if on an account that isn't registered route to /
    if (inAccounts && !accounts.some(account => currentURL.startsWith('/account/' + account.code))) {
      navigate('/');
      return;
    }
    // if on index page and have at least 1 account, route to /account-summary
    if (isIndex && accounts.length) {
      // replace current history entry so that the user cannot go back to "index"
      navigate('/account-summary?with-chart-animation=true', { replace: true });
      return;
    }
    // if on the /exchange/ view and there are no accounts view route to /
    if (accounts.length === 0 && currentURL.startsWith('/exchange/')) {
      navigate('/');
      return;
    }
    // if on the /bitsurance/ view and there are no accounts view route to /
    if (accounts.length === 0 && currentURL.startsWith('/bitsurance/')) {
      navigate('/');
      return;
    }
    // if in no-accounts settings and has account go to manage-accounts
    if (accounts.length && currentURL === '/settings/no-accounts') {
      navigate('/settings/manage-accounts');
      return;
    }

  }, [accounts, devices, navigate]);

  useEffect(() => {
    const oldDeviceIDList = Object.keys(prevDevices || {});
    const newDeviceIDList: string[] = Object.keys(devices);

    // If a device is newly connected, we route to the settings.
    if (
      newDeviceIDList.length > 0
      && newDeviceIDList[0] !== oldDeviceIDList[0]
    ) {
      // We only route to settings if it is a bb01 or a bb02 bootloader.
      // The bitbox02 wizard itself is mounted globally (see BitBox02Wizard) so it can be unlocked
      // anywhere at any time.
      // We don't bother implementing the same for the bitbox01.
      // The bb02 bootloader screen is not full screen, so we don't mount it globally and instead
      // route to it.
      const productName = devices[newDeviceIDList[0]];
      if (productName === 'bitbox' || productName === 'bitbox02-bootloader') {
        navigate(`settings/device-settings/${newDeviceIDList[0]}`);
        return;
      }
    }
    maybeRoute();
  }, [devices, maybeRoute, navigate, prevDevices]);

  // Returns a string representation of the current devices, so it can be used in the `key` property of subcomponents.
  // The prefix is used so different subcomponents can have unique keys to not confuse the renderer.
  const devicesKey = (prefix: string): string => {
    return prefix + ':' + JSON.stringify(devices, Object.keys(devices).sort());
  };

  const deviceIDs: string[] = Object.keys(devices);
  const activeAccounts = accounts.filter(acct => acct.active);

  const isBitboxBootloader = devices[deviceIDs[0]] === 'bitbox02-bootloader';
  const showBottomNavigation = (deviceIDs.length > 0 || activeAccounts.length > 0) && !isBitboxBootloader;

  return (
    <ConnectedApp>
      <Providers>
        <Darkmode />
        <div className="app">
          <AuthRequired/>
          <Sidebar
            accounts={activeAccounts}
            devices={devices}
          />
          <div className={`${styles.appContent} ${showBottomNavigation ? styles.hasBottomNavigation : ''}`}>
            <WCSigningRequest />
            <Aopp />
            <KeystoreConnectPrompt />
            {
              Object.entries(devices).map(([deviceID, platformName]) => {
                if (platformName === 'bitbox02') {
                  return (
                    <Fragment key={deviceID}>
                      <BitBox02Wizard
                        deviceID={deviceID}
                      />
                    </Fragment>
                  );
                }
                return null;
              })
            }
            <div className={styles.contentContainer}>
              <GlobalBanners />
              <AppRouter
                accounts={accounts}
                activeAccounts={activeAccounts}
                deviceIDs={deviceIDs}
                devices={devices}
                devicesKey={devicesKey}
              />
            </div>
            <RouterWatcher />
          </div>
          {showBottomNavigation && (
            <BottomNavigation activeAccounts={activeAccounts} />
          )}
          <Alert />
          <Confirm />
        </div>
      </Providers>
    </ConnectedApp>
  );
};
