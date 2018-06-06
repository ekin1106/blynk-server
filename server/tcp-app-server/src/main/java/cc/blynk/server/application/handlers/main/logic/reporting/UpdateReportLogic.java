package cc.blynk.server.application.handlers.main.logic.reporting;

import cc.blynk.server.Holder;
import cc.blynk.server.core.model.DashBoard;
import cc.blynk.server.core.model.auth.User;
import cc.blynk.server.core.model.serialization.JsonParser;
import cc.blynk.server.core.model.widgets.ui.reporting.Report;
import cc.blynk.server.core.model.widgets.ui.reporting.ReportScheduler;
import cc.blynk.server.core.model.widgets.ui.reporting.ReportingWidget;
import cc.blynk.server.core.protocol.exceptions.IllegalCommandBodyException;
import cc.blynk.server.core.protocol.exceptions.IllegalCommandException;
import cc.blynk.server.core.protocol.model.messages.StringMessage;
import cc.blynk.utils.ArrayUtil;
import io.netty.channel.ChannelHandlerContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static cc.blynk.server.internal.CommonByteBufUtil.ok;
import static cc.blynk.utils.StringUtils.split2;

/**
 * The Blynk Project.
 * Created by Dmitriy Dumanskiy.
 * Created on 31/05/2018.
 *
 */
public class UpdateReportLogic {

    private static final Logger log = LogManager.getLogger(UpdateReportLogic.class);

    private final ReportScheduler reportScheduler;

    public UpdateReportLogic(Holder holder) {
        this.reportScheduler = holder.reportScheduler;
    }

    public void messageReceived(ChannelHandlerContext ctx, User user, StringMessage message) {
        String[] split = split2(message.body);

        if (split.length < 2) {
            throw new IllegalCommandException("Wrong income message format.");
        }

        int dashId = Integer.parseInt(split[0]);
        String reportJson = split[1];

        if (reportJson == null || reportJson.isEmpty()) {
            throw new IllegalCommandException("Income report message is empty.");
        }

        DashBoard dash = user.profile.getDashByIdOrThrow(dashId);
        ReportingWidget reportingWidget = dash.getReportingWidget();

        if (reportingWidget == null) {
            throw new IllegalCommandException("Project has no reporting widget.");
        }

        Report report = JsonParser.parseReport(reportJson, message.id);

        int existingReportIndex = reportingWidget.getReportIndexById(report.id);
        if (existingReportIndex == -1) {
            throw new IllegalCommandException("Cannot find report with provided id.");
        }

        reportingWidget.reports = ArrayUtil.copyAndReplace(reportingWidget.reports, report, existingReportIndex);
        dash.updatedAt = System.currentTimeMillis();

        //always remove prev report before any validations are done
        if (report.isPeriodic()) {
            boolean isRemoved = reportScheduler.cancelStoredFuture(user, dashId, report.id);
            log.debug("Deleting reportId {} in scheduler for {}. Is removed: {}?.",
                    report.id, user.email, isRemoved);
        }

        if (!report.isValid()) {
            log.debug("Report is not valid {} for {}.", report, user.email);
            throw new IllegalCommandException("Report is not valid.");
        }

        if (report.isPeriodic()) {
            long initialDelaySeconds;
            try {
                initialDelaySeconds = report.calculateDelayInSeconds();
            } catch (IllegalCommandBodyException e) {
                //re throw, quick workaround
                log.debug("Report has wrong configuration for {}. Report : {}", user.email, report);
                throw new IllegalCommandBodyException(e.getMessage(), message.id);
            }

            log.info("Adding periodic report for user {} with delay {} to scheduler.",
                    user.email, initialDelaySeconds);
            log.debug(reportJson);

            report.nextReportAt = System.currentTimeMillis() + initialDelaySeconds * 1000;

            if (report.isActive) {
                reportScheduler.schedule(user, dashId, report, initialDelaySeconds);
            }
        }

        ctx.writeAndFlush(ok(message.id), ctx.voidPromise());
    }

}
