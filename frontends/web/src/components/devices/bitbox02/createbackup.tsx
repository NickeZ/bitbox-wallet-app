/**
 * Copyright 2018 Shift Devices AG
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

import { Component, h, RenderableProps } from 'preact';
import { translate, TranslateProps } from '../../../decorators/translate';
import { apiPost } from '../../../utils/request';
import { alertUser } from '../../alert/Alert';
import { confirmation } from '../../confirm/Confirm';
import { Button } from '../../forms';
import WaitDialog from '../../wait-dialog/wait-dialog';

interface CreateProps {
    deviceID: string;
}

type Props = CreateProps & TranslateProps;

interface State {
    creatingBackup: boolean;
    disabled: boolean;
}

class Create extends Component<Props, State> {
    public state = {
        creatingBackup: false,
        disabled: false,
    };

    private createBackup = () => {
        this.setState({ creatingBackup: true });
        apiPost('devices/bitbox02/' + this.props.deviceID + '/backups/create').then(({ success }) => {
            if (!success) {
                alertUser(this.props.t('backup.create.fail'));
            }
            this.setState({ creatingBackup: false, disabled: false });
        });
    }

    private maybeCreateBackup = () => {
        this.setState({ disabled: true });
        apiPost('devices/bitbox02/' + this.props.deviceID + '/backups/check', {
            silent: true,
        }).then(check => {
            if (check.success) {
                confirmation(this.props.t('backup.create.alreadyExists'), result => {
                    if (result) {
                        this.createBackup();
                    } else {
                        this.setState({ disabled: false });
                    }
                });
                return;
            }
            this.createBackup();
        });
    }

    public render(
        { t }: RenderableProps<Props>,
        { creatingBackup,
          disabled,
        }: State) {
        return (
            <span>
                <Button
                    primary
                    disabled={disabled}
                    onClick={() => this.maybeCreateBackup()}>
                    {t('backup.create.title')}
                </Button>
                { creatingBackup && (
                      <WaitDialog
                          title={t('backup.create.title')}
                      >
                          {t('bitbox02Interact.followInstructions')}
                      </WaitDialog>
                )}
            </span>
        );
    }
}

const HOC = translate<CreateProps>()(Create);
export { HOC as Create };
