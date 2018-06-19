import { Component, h } from 'preact';
import { translate } from 'react-i18next';
import { Button } from '../../../../components/forms';
import Dialog from '../../../../components/dialog/dialog';
import WaitDialog from '../../../../components/wait-dialog/wait-dialog';
import { PasswordRepeatInput } from '../../../../components/password';
import { apiPost } from '../../../../utils/request';
import InnerHTMLHelper from '../../../../utils/innerHTML';


@translate()
export default class HiddenWallet extends Component {
    state = {
        pin: null,
        errorCode: null,
        isConfirming: false,
        activeDialog: false,
    }

    componentWillMount() {
        document.addEventListener('keydown', this.handleKeyDown);
    }

    componentWillUnmount() {
        document.removeEventListener('keydown', this.handleKeyDown);
    }

    handleKeyDown = e => {
        const {
            isConfirming,
        } = this.state;
        if (e.keyCode === 27 && !isConfirming) {
            this.abort();
        } else {
            return;
        }
    }

    abort = () => {
        this.setState({
            password: null,
            isConfirming: false,
            activeDialog: false,
        });
        if (this.pinInput) {
            this.pinInput.clear();
        }
    }

    handleFormChange = event => {
        this.setState({ [event.target.id]: event.target.value });
    }

    validate = () => {
        return this.state.pin;
    }

    createHiddenWallet = event => {
        event.preventDefault();
        if (!this.validate()) return;
        this.setState({
            activeDialog: false,
            isConfirming: true,
        });
        apiPost('devices/' + this.props.deviceID + '/set-password', {
            password: this.state.pin,
        }).catch(() => {}).then(data => {
            this.abort();

            if (!data.success) {
                if (data.code) {
                    this.setState({ errorCode: data.code });
                }
                alert(data.errorMessage);
                // {t(`initialize.error.${errorCode}`, {
                //         defaultValue: errorMessage
                //     })}
                // this.setState({
                //     status: stateEnum.ERROR,
                //     errorMessage: data.errorMessage
                // });
            }
        });
    }

    setValidPIN = pin => {
        this.setState({ pin });
    }

    render({
        t,
        disabled,
    }, {
        isConfirming,
        activeDialog,
    }) {
        return (
            <span>
                <Button
                    primary
                    disabled={disabled}
                    onclick={() => this.setState({ activeDialog: true })}>
                    {t('button.changepin')}
                </Button>
                {
                    activeDialog && (
                        <Dialog title={t('button.changepin')}>
                            <form onSubmit={this.createHiddenWallet}>
                                <PasswordRepeatInput
                                    idPrefix="pin"
                                    pattern="^[0-9]+$"
                                    title={t('initialize.input.invalid')}
                                    label={t('initialize.input.label')}
                                    repeatLabel={t('initialize.input.labelRepeat')}
                                    repeatPlaceholder={t('initialize.input.placeholderRepeat')}
                                    ref={ref => this.pinInput = ref}
                                    onValidPassword={this.setValidPIN} />
                                <div class={['buttons', 'flex', 'flex-row', 'flex-end'].join(' ')}>
                                    <Button secondary onClick={this.abort} disabled={isConfirming}>
                                        {t('button.abort')}
                                    </Button>
                                    <Button type="submit" danger disabled={!this.validate() || isConfirming}>
                                        {t('button.changepin')}
                                    </Button>
                                </div>
                            </form>
                        </Dialog>
                    )
                }
                {
                    isConfirming && (
                        <WaitDialog title={t('button.changepin')} />
                    )
                }
            </span>
        );
    }
}