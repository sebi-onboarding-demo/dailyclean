﻿import FormConfigurationContainer, {
    messageEndDateShouldBeAfterStartDate,
    messageStartHourShouldBeBeforeEndHour
} from "./FormConfigurationContainer";
import sleep from './sleep';
import '@testing-library/jest-dom';
import {render, fireEvent, screen, waitFor, act} from '@testing-library/react';
import React from "react";
import { describe, it, expect } from 'vitest';

const fetch = (status =200, getCallback, postCallback) => async (url, config) => {
    if(config.method === "POST") {
        postCallback(config.body);
    }
    await sleep(1);
    return {
        status:status,
        json: async () => {
            await sleep(1);
            return getCallback();
        },
    };
};

describe(`FormConfigurationContainer`, () => {

    it(`submit should save data with success`, async () => {
        const postCallback = vi.fn().mockImplementation(x => x);
        const getCallback = vi.fn().mockImplementation(x => { return {"cron_start":"0 7 * * 1-5","cron_stop":"0 17 * * *"}});
        const setConfigurationState = () => console.log("setConfigurationState");
        const utils = render(<FormConfigurationContainer fetch={fetch(200, getCallback, postCallback)} setConfigurationState={setConfigurationState}/>);

        await waitFor(() => expect(getCallback).toHaveBeenCalledTimes(1));

        const title = screen.queryByText("Configuration");
        expect(title).toBeTruthy();
        expect(screen.getByRole('button')).toHaveAttribute('disabled');

        const inputStart = utils.getByLabelText('Start hour');
        fireEvent.change(inputStart, { target: { value: '2' } })

        const item = screen.queryByText("All days");
        fireEvent.click(item);

        const inputEnd = utils.getByLabelText('End hour');
        fireEvent.change(inputEnd, { target: { value: '18' } });
        expect(screen.getByRole('button')).not.toHaveAttribute('disabled');

        const fireSumbit = () => {
            const item = screen.queryByText("Submit");
            fireEvent.click(item);
        };

        fireSumbit();
        await waitFor(() => screen.getByRole('alert'));

        expect(postCallback.mock.calls.length).toBe(1);
        expect(postCallback.mock.calls[0][0]).toBe("{\"cron_start\":\"0 2 * * *\",\"cron_stop\":\"0 18 * * *\"}");

        expect(screen.getByRole('alert')).toHaveTextContent("SuccessSave done succesfully.");
        expect(screen.getByRole('button')).toHaveAttribute('disabled');

        const workedDays = screen.queryByText("Working days (Monday to Friday)");
        fireEvent.click(workedDays);

        fireSumbit();
        await waitFor(() => screen.getByRole('alert'));

        expect(postCallback.mock.calls.length).toBe(2);
        expect(postCallback.mock.calls[1][0]).toBe("{\"cron_start\":\"0 2 * * 1-5\",\"cron_stop\":\"0 18 * * *\"}");

        expect(screen.getByRole('alert')).toHaveTextContent("SuccessSave done succesfully.");
        expect(screen.getByRole('button')).toHaveAttribute('disabled');

    });

    it(`error message should display with success`, async () => {
        const postCallback = vi.fn().mockImplementation(x => x);
        const getCallback = vi.fn().mockImplementation(x => { return {"cron_start":"0 7 * * 1-5","cron_stop":"0 17 * * *"}});
        const setConfigurationState = () => console.log("setConfigurationState");
        const utils = render(<FormConfigurationContainer fetch={fetch(200, getCallback, postCallback)} setConfigurationState={setConfigurationState}/>);

        await waitFor(() => expect(getCallback).toHaveBeenCalledTimes(1));

        await act(async () => {
            const title = screen.queryByText("Configuration");
            expect(title).toBeTruthy();
            expect(screen.getByRole('button')).toHaveAttribute('disabled');

            const inputStart = utils.getByLabelText('Start hour');
            inputStart.focus();
            fireEvent.change(inputStart, {target: {value: '20'}})
            inputStart.blur();

            const inputEnd = utils.getByLabelText('End hour');
            inputEnd.focus();
            fireEvent.change(inputEnd, {target: {value: '20'}});
            inputEnd.blur();
        });

        const endErrorMessage = screen.getByText(messageEndDateShouldBeAfterStartDate);
        expect(endErrorMessage).toBeTruthy();

        expect(screen.getByRole('button')).toHaveAttribute('disabled');
    });

});
