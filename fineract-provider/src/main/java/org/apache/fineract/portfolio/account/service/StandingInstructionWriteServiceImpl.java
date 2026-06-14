/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.portfolio.account.service;

import static org.apache.fineract.portfolio.account.AccountDetailConstants.transferTypeParamName;
import static org.apache.fineract.portfolio.account.api.StandingInstructionApiConstants.amountParamName;
import static org.apache.fineract.portfolio.account.api.StandingInstructionApiConstants.instructionTypeParamName;
import static org.apache.fineract.portfolio.account.api.StandingInstructionApiConstants.nameParamName;
import static org.apache.fineract.portfolio.account.api.StandingInstructionApiConstants.priorityParamName;
import static org.apache.fineract.portfolio.account.api.StandingInstructionApiConstants.recurrenceFrequencyParamName;
import static org.apache.fineract.portfolio.account.api.StandingInstructionApiConstants.recurrenceIntervalParamName;
import static org.apache.fineract.portfolio.account.api.StandingInstructionApiConstants.recurrenceOnMonthDayParamName;
import static org.apache.fineract.portfolio.account.api.StandingInstructionApiConstants.recurrenceTypeParamName;
import static org.apache.fineract.portfolio.account.api.StandingInstructionApiConstants.statusParamName;
import static org.apache.fineract.portfolio.account.api.StandingInstructionApiConstants.validFromParamName;
import static org.apache.fineract.portfolio.account.api.StandingInstructionApiConstants.validTillParamName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.ErrorHandler;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.portfolio.account.PortfolioAccountType;
import org.apache.fineract.portfolio.account.api.StandingInstructionApiConstants;
import org.apache.fineract.portfolio.account.data.request.StandingInstructionCreationRequest;
import org.apache.fineract.portfolio.account.data.request.StandingInstructionDeleteRequest;
import org.apache.fineract.portfolio.account.data.request.StandingInstructionUpdatesRequest;
import org.apache.fineract.portfolio.account.data.response.StandingInstructionCreateResponse;
import org.apache.fineract.portfolio.account.data.response.StandingInstructionDeleteResponse;
import org.apache.fineract.portfolio.account.data.response.StandingInstructionUpdateResponse;
import org.apache.fineract.portfolio.account.domain.AccountTransferDetailAssembler;
import org.apache.fineract.portfolio.account.domain.AccountTransferDetailRepository;
import org.apache.fineract.portfolio.account.domain.AccountTransferDetails;
import org.apache.fineract.portfolio.account.domain.AccountTransferRecurrenceType;
import org.apache.fineract.portfolio.account.domain.AccountTransferStandingInstruction;
import org.apache.fineract.portfolio.account.domain.AccountTransferType;
import org.apache.fineract.portfolio.account.domain.StandingInstructionRepository;
import org.apache.fineract.portfolio.account.domain.StandingInstructionType;
import org.apache.fineract.portfolio.account.exception.StandingInstructionNotFoundException;
import org.apache.fineract.portfolio.common.domain.PeriodFrequencyType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.NonTransientDataAccessException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@RequiredArgsConstructor
public class StandingInstructionWriteServiceImpl implements StandingInstructionWriteService {

    private final AccountTransferDetailAssembler accountTransferDetailAssembler;
    private final AccountTransferDetailRepository accountTransferDetailRepository;
    private final StandingInstructionRepository standingInstructionRepository;

    @Transactional
    @Override
    public StandingInstructionCreateResponse create(final StandingInstructionCreationRequest request) {
        final LocalDate validFrom = parseDate(request.getValidFrom(), request.getDateFormat(), request.getLocale());
        final LocalDate validTill = parseDate(request.getValidTill(), request.getDateFormat(), request.getLocale());
        final MonthDay recurrenceOnMonthDay = parseMonthDay(request.getRecurrenceOnMonthDay(), request.getMonthDayFormat(),
                request.getLocale());

        validateForCreate(request, validFrom, validTill, recurrenceOnMonthDay);

        final PortfolioAccountType fromAccountType = PortfolioAccountType.fromInt(request.getFromAccountType());
        final PortfolioAccountType toAccountType = PortfolioAccountType.fromInt(request.getToAccountType());

        Long standingInstructionId = null;
        try {
            final AccountTransferDetails details = assembleAccountTransferDetails(request, fromAccountType, toAccountType);

            BigDecimal amount = request.getAmount();
            if (amount != null && details.fromSavingsAccount() != null) {
                amount = Money.of(details.fromSavingsAccount().getCurrency(), amount).getAmount();
            }

            final AccountTransferStandingInstruction standingInstruction = AccountTransferStandingInstruction.create(details,
                    request.getName(), request.getPriority(), request.getInstructionType(), request.getStatus(), amount, validFrom,
                    validTill, request.getRecurrenceType(), request.getRecurrenceFrequency(), request.getRecurrenceInterval(),
                    recurrenceOnMonthDay);
            details.updateAccountTransferStandingInstruction(standingInstruction);

            this.accountTransferDetailRepository.saveAndFlush(details);
            standingInstructionId = details.accountTransferStandingInstruction().getId();
        } catch (final JpaSystemException | DataIntegrityViolationException dve) {
            handleDataIntegrityIssues(request.getName(), dve.getMostSpecificCause(), dve);
        }

        return StandingInstructionCreateResponse.builder().resourceId(standingInstructionId).clientId(request.getFromClientId()).build();
    }

    @Transactional
    @Override
    public StandingInstructionUpdateResponse update(final StandingInstructionUpdatesRequest request) {
        final LocalDate validFrom = parseDate(request.getValidFrom(), request.getDateFormat(), request.getLocale());
        final LocalDate validTill = parseDate(request.getValidTill(), request.getDateFormat(), request.getLocale());
        final MonthDay recurrenceOnMonthDay = parseMonthDay(request.getRecurrenceOnMonthDay(), request.getMonthDayFormat(),
                request.getLocale());

        validateForUpdate(request, validFrom, validTill);

        final AccountTransferStandingInstruction standingInstruction = this.standingInstructionRepository.findById(request.getId())
                .orElseThrow(() -> new StandingInstructionNotFoundException(request.getId()));

        final Map<String, Object> changes = standingInstruction.update(request.getAmount(), validFrom, validTill, request.getStatus(),
                request.getPriority(), request.getInstructionType(), request.getRecurrenceType(), request.getRecurrenceFrequency(),
                request.getRecurrenceInterval(), recurrenceOnMonthDay);

        if (!changes.isEmpty()) {
            this.standingInstructionRepository.save(standingInstruction);
        }

        return StandingInstructionUpdateResponse.builder().resourceId(request.getId()).changes(changes).build();
    }

    @Transactional
    @Override
    public StandingInstructionDeleteResponse delete(final StandingInstructionDeleteRequest request) {
        final AccountTransferStandingInstruction standingInstruction = this.standingInstructionRepository.findById(request.getId())
                .orElseThrow(() -> new StandingInstructionNotFoundException(request.getId()));
        standingInstruction.delete();
        this.standingInstructionRepository.save(standingInstruction);
        return StandingInstructionDeleteResponse.builder().resourceId(request.getId()).build();
    }

    private AccountTransferDetails assembleAccountTransferDetails(final StandingInstructionCreationRequest request,
            final PortfolioAccountType fromAccountType, final PortfolioAccountType toAccountType) {
        if (PortfolioAccountType.SAVINGS.equals(fromAccountType) && PortfolioAccountType.SAVINGS.equals(toAccountType)) {
            return this.accountTransferDetailAssembler.assembleSavingsToSavingsTransfer(request.getFromAccountId(),
                    request.getToAccountId(), request.getTransferType());
        } else if (PortfolioAccountType.SAVINGS.equals(fromAccountType) && PortfolioAccountType.LOAN.equals(toAccountType)) {
            return this.accountTransferDetailAssembler.assembleSavingsToLoanTransfer(request.getFromAccountId(), request.getToAccountId(),
                    request.getTransferType());
        } else if (PortfolioAccountType.LOAN.equals(fromAccountType) && PortfolioAccountType.SAVINGS.equals(toAccountType)) {
            return this.accountTransferDetailAssembler.assembleLoanToSavingsTransfer(request.getFromAccountId(), request.getToAccountId(),
                    request.getTransferType());
        }
        throw new PlatformDataIntegrityException("error.msg.standinginstruction.transfer.type.not.supported",
                "Transfer between the given account types is not supported for standing instructions");
    }

    private void validateForCreate(final StandingInstructionCreationRequest request, final LocalDate validFrom, final LocalDate validTill,
            final MonthDay recurrenceOnMonthDay) {
        final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
        final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors)
                .resource(StandingInstructionApiConstants.STANDING_INSTRUCTION_RESOURCE_NAME);

        baseDataValidator.reset().parameter(statusParamName).value(request.getStatus()).notNull().inMinMaxRange(1, 2);
        baseDataValidator.reset().parameter(validFromParamName).value(validFrom).notNull();
        baseDataValidator.reset().parameter(validTillParamName).value(validTill).validateDateAfter(validFrom);
        baseDataValidator.reset().parameter(amountParamName).value(request.getAmount()).positiveAmount();
        baseDataValidator.reset().parameter(transferTypeParamName).value(request.getTransferType()).notNull().inMinMaxRange(1, 3);
        baseDataValidator.reset().parameter(priorityParamName).value(request.getPriority()).notNull().inMinMaxRange(1, 4);

        final Integer instructionType = request.getInstructionType();
        baseDataValidator.reset().parameter(instructionTypeParamName).value(instructionType).notNull().inMinMaxRange(1, 2);

        final Integer recurrenceType = request.getRecurrenceType();
        baseDataValidator.reset().parameter(recurrenceTypeParamName).value(recurrenceType).notNull().inMinMaxRange(1, 2);
        boolean isPeriodic = false;
        if (recurrenceType != null) {
            isPeriodic = AccountTransferRecurrenceType.fromInt(recurrenceType).isPeriodicRecurrence();
        }

        final Integer recurrenceFrequency = request.getRecurrenceFrequency();
        baseDataValidator.reset().parameter(recurrenceFrequencyParamName).value(recurrenceFrequency).inMinMaxRange(0, 3);
        if (recurrenceFrequency != null) {
            final PeriodFrequencyType frequencyType = PeriodFrequencyType.fromInt(recurrenceFrequency);
            if (frequencyType.isMonthly() || frequencyType.isYearly()) {
                baseDataValidator.reset().parameter(recurrenceOnMonthDayParamName).value(recurrenceOnMonthDay).notNull();
            }
        }

        final Integer recurrenceInterval = request.getRecurrenceInterval();
        if (isPeriodic) {
            baseDataValidator.reset().parameter(recurrenceIntervalParamName).value(recurrenceInterval).notNull();
            baseDataValidator.reset().parameter(recurrenceFrequencyParamName).value(recurrenceFrequency).notNull();
        }
        baseDataValidator.reset().parameter(recurrenceIntervalParamName).value(recurrenceInterval).integerGreaterThanZero();

        baseDataValidator.reset().parameter(nameParamName).value(request.getName()).notNull();

        final Integer toAccountType = request.getToAccountType();
        if (toAccountType != null && PortfolioAccountType.SAVINGS.equals(PortfolioAccountType.fromInt(toAccountType))) {
            baseDataValidator.reset().parameter(instructionTypeParamName).value(instructionType).notNull().inMinMaxRange(1, 1);
            baseDataValidator.reset().parameter(recurrenceTypeParamName).value(recurrenceType).notNull().inMinMaxRange(1, 1);
        }
        if (instructionType != null && StandingInstructionType.fromInt(instructionType).isFixedAmoutTransfer()) {
            baseDataValidator.reset().parameter(amountParamName).value(request.getAmount()).notNull();
        }

        final Integer transferType = request.getTransferType();
        final Integer fromAccountType = request.getFromAccountType();
        if (transferType != null && fromAccountType != null && toAccountType != null) {
            final AccountTransferType accountTransferType = AccountTransferType.fromInt(transferType);
            final PortfolioAccountType fromPortfolioAccountType = PortfolioAccountType.fromInt(fromAccountType);
            final PortfolioAccountType toPortfolioAccountType = PortfolioAccountType.fromInt(toAccountType);
            String errorCode = null;
            if (accountTransferType.isAccountTransfer() && (PortfolioAccountType.LOAN.equals(fromPortfolioAccountType)
                    || PortfolioAccountType.LOAN.equals(toPortfolioAccountType))) {
                errorCode = "not.account.transfer";
            } else if (accountTransferType.isLoanRepayment() && (PortfolioAccountType.LOAN.equals(fromPortfolioAccountType)
                    || PortfolioAccountType.SAVINGS.equals(toPortfolioAccountType))) {
                errorCode = "not.loan.repayment";
            }
            if (errorCode != null) {
                baseDataValidator.reset().parameter(transferTypeParamName).failWithCode(errorCode);
            }
        }

        throwExceptionIfValidationWarningsExist(dataValidationErrors);
    }

    private void validateForUpdate(final StandingInstructionUpdatesRequest request, final LocalDate validFrom, final LocalDate validTill) {
        final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
        final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors)
                .resource(StandingInstructionApiConstants.STANDING_INSTRUCTION_RESOURCE_NAME);

        if (request.getValidFrom() != null) {
            baseDataValidator.reset().parameter(validFromParamName).value(validFrom).notNull();
        }
        if (request.getValidTill() != null) {
            baseDataValidator.reset().parameter(validTillParamName).value(validTill).notNull();
        }
        if (request.getAmount() != null) {
            baseDataValidator.reset().parameter(amountParamName).value(request.getAmount()).positiveAmount();
        }
        if (request.getStatus() != null) {
            baseDataValidator.reset().parameter(statusParamName).value(request.getStatus()).notNull().inMinMaxRange(1, 2);
        }
        if (request.getPriority() != null) {
            baseDataValidator.reset().parameter(priorityParamName).value(request.getPriority()).notNull().inMinMaxRange(1, 4);
        }
        if (request.getInstructionType() != null) {
            baseDataValidator.reset().parameter(instructionTypeParamName).value(request.getInstructionType()).notNull().inMinMaxRange(1, 2);
        }
        if (request.getRecurrenceType() != null) {
            baseDataValidator.reset().parameter(recurrenceTypeParamName).value(request.getRecurrenceType()).notNull().inMinMaxRange(1, 2);
        }
        if (request.getRecurrenceFrequency() != null) {
            baseDataValidator.reset().parameter(recurrenceFrequencyParamName).value(request.getRecurrenceFrequency()).inMinMaxRange(0, 3);
        }
        if (request.getRecurrenceInterval() != null) {
            baseDataValidator.reset().parameter(recurrenceIntervalParamName).value(request.getRecurrenceInterval())
                    .integerGreaterThanZero();
        }
        if (request.getName() != null) {
            baseDataValidator.reset().parameter(nameParamName).value(request.getName()).notNull();
        }

        throwExceptionIfValidationWarningsExist(dataValidationErrors);
    }

    private void throwExceptionIfValidationWarningsExist(final List<ApiParameterError> dataValidationErrors) {
        if (!dataValidationErrors.isEmpty()) {
            throw new PlatformApiDataValidationException(dataValidationErrors);
        }
    }

    private void handleDataIntegrityIssues(final String name, final Throwable realCause, final NonTransientDataAccessException dve) {
        if (realCause.getMessage() != null && realCause.getMessage().contains("name")) {
            throw new PlatformDataIntegrityException("error.msg.standinginstruction.duplicate.name",
                    "Standinginstruction with name `" + name + "` already exists", "name", name);
        }
        log.error("Error occured.", dve);
        throw ErrorHandler.getMappable(dve, "error.msg.client.unknown.data.integrity.issue",
                "Unknown data integrity issue with resource: " + realCause.getMessage());
    }

    private static LocalDate parseDate(final String value, final String dateFormat, final String locale) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        if (StringUtils.isBlank(dateFormat)) {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
        }
        return LocalDate.parse(value, formatter(dateFormat, locale));
    }

    private static MonthDay parseMonthDay(final String value, final String monthDayFormat, final String locale) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        final String pattern = StringUtils.isBlank(monthDayFormat) ? "dd MMMM" : monthDayFormat;
        return MonthDay.parse(value, formatter(pattern, locale));
    }

    private static DateTimeFormatter formatter(final String pattern, final String locale) {
        final Locale loc = StringUtils.isBlank(locale) ? Locale.getDefault() : Locale.forLanguageTag(locale.replace('_', '-'));
        return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(loc);
    }
}
