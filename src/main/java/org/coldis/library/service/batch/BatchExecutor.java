package org.coldis.library.service.batch;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.MethodUtils;
import org.coldis.library.exception.BusinessException;
import org.coldis.library.exception.IntegrationException;
import org.coldis.library.helper.DateTimeHelper;
import org.coldis.library.model.SimpleMessage;
import org.coldis.library.model.Typable;
import org.coldis.library.model.view.ModelView;
import org.coldis.library.persistence.bean.StaticContextAccessor;
import org.coldis.library.serialization.ObjectMapperHelper;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Batch executor.
 */
@JsonTypeName(value = BatchExecutor.TYPE_NAME)
public class BatchExecutor<Type> implements Typable {

	/**
	 * Serial.
	 */
	private static final long serialVersionUID = 3022111202119271553L;

	/**
	 * Type name.
	 */
	public static final String TYPE_NAME = "org.coldis.library.service.batch.BatchExecutor";

	/**
	 * Object mapper.
	 */
	public static final ObjectMapper OBJECT_MAPPER;
	static {
		OBJECT_MAPPER = ObjectMapperHelper.createMapper();
		BatchExecutor.OBJECT_MAPPER.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
	}

	/**
	 * Batch item itemType name.
	 */
	private String itemTypeName;

	/**
	 * Key suffix.
	 */
	private String keySuffix;

	/**
	 * Batch size.
	 */
	private Long size = 13000L;

	/**
	 * Last processed.
	 */
	private Type lastProcessed;

	/**
	 * Delay to add between runs.
	 */
	private Duration delayBetweenRuns;

	/**
	 * Maximum interval to finish the batch.
	 */
	private Duration finishWithin;

	/**
	 * Maximum interval to keep the batch persisted.
	 */
	private Duration cleansWithin;

	/**
	 * Arguments used to get next batch.
	 */
	private Map<String, String> arguments;

	/**
	 * Action bean name.
	 */
	private String actionBeanName;

	/**
	 * Action delegate methods.
	 */
	private Map<BatchAction, String> actionDelegateMethods;

	/**
	 * Messages templates.
	 */
	private Map<BatchAction, String> messagesTemplates;

	/**
	 * Slack channels to communicate.
	 */
	private Map<BatchAction, String> slackChannels;

	/**
	 * Last started at.
	 */
	private LocalDateTime lastStartedAt;

	/**
	 * Last finished at.
	 */
	private LocalDateTime lastFinishedAt;

	/**
	 * When the batch was cancelled.
	 */
	private LocalDateTime lastCancelledAt;

	/**
	 * Last processed count.
	 */
	private Long lastProcessedCount;

	/**
	 * No arguments constructor.
	 */
	protected BatchExecutor() {
		super();
	}

	/**
	 * No arguments constructor.
	 */
	public BatchExecutor(final Class<Type> itemType) {
		super();
		this.itemTypeName = itemType.getName();
	}

	/**
	 * Default constructor.
	 *
	 * @param keySuffix     Key suffix.
	 * @param size          Size.
	 * @param lastProcessed Last processed.
	 * @param finishWithin  Maximum interval to finish the batch.
	 */
	public BatchExecutor(final Class<Type> itemType, final String keySuffix, final Long size, final Type lastProcessed, final Duration finishWithin) {
		super();
		this.itemTypeName = itemType.getName();
		this.keySuffix = keySuffix;
		this.size = size;
		this.lastProcessed = lastProcessed;
		this.finishWithin = finishWithin;
	}

	/**
	 * Complete constructor.
	 *
	 * @param keySuffix             Key suffix.Factory
	 * @param size                  Size.
	 * @param lastProcessed         Last processed.
	 * @param finishWithin          Maximum interval to finish the batch.
	 * @param actionBeanName        Action bean name.
	 * @param actionDelegateMethods Action delegate methods.
	 * @param messagesTemplates     Messages templates.
	 * @param slackChannels         Slack channels.
	 */
	public BatchExecutor(
			final Class<Type> itemType,
			final String keySuffix,
			final Long size,
			final Type lastProcessed,
			final Duration delayBetweenRuns,
			final Duration finishWithin,
			final Duration cleansWithin,
			final String actionBeanName,
			final Map<BatchAction, String> actionDelegateMethods,
			final Map<BatchAction, String> messagesTemplates,
			final Map<BatchAction, String> slackChannels) {
		super();
		this.itemTypeName = itemType.getName();
		this.keySuffix = keySuffix;
		this.size = size;
		this.lastProcessed = lastProcessed;
		this.delayBetweenRuns = delayBetweenRuns;
		this.finishWithin = finishWithin;
		this.cleansWithin = cleansWithin;
		this.actionBeanName = actionBeanName;
		this.actionDelegateMethods = actionDelegateMethods;
		this.messagesTemplates = messagesTemplates;
		this.slackChannels = slackChannels;
	}

	/**
	 * Gets the itemTypeName.
	 *
	 * @return The itemTypeName.
	 */
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public String getItemTypeName() {
		return this.itemTypeName;
	}

	/**
	 * Sets the itemTypeName.
	 *
	 * @param itemTypeName New itemTypeName.
	 */
	public void setItemTypeName(
			final String itemTypeName) {
		this.itemTypeName = itemTypeName;
	}

	/**
	 * Gets the itemType.
	 *
	 * @return The itemType.
	 */
	@JsonIgnore
	@SuppressWarnings("unchecked")
	public Class<Type> getType() {
		try {
			return (this.getItemTypeName() == null ? null : (Class<Type>) Class.forName(this.getItemTypeName()));
		}
		catch (final Exception exception) {
			throw new IntegrationException(new SimpleMessage("type.ivalid"));
		}
	}

	/**
	 * Gets the keySuffix.
	 *
	 * @return The keySuffix.
	 */
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public String getKeySuffix() {
		return this.keySuffix;
	}

	/**
	 * Sets the keySuffix.
	 *
	 * @param keySuffix New keySuffix.
	 */
	public void setKeySuffix(
			final String keySuffix) {
		this.keySuffix = keySuffix;
	}

	/**
	 * Gets the size.
	 *
	 * @return The size.
	 */
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public Long getSize() {
		return this.size;
	}

	/**
	 * Sets the size.
	 *
	 * @param size New size.
	 */
	public void setSize(
			final Long size) {
		this.size = size;
	}

	/**
	 * Gets the lastProcessed.
	 *
	 * @return The lastProcessed.
	 */
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public Type getLastProcessed() {
		this.lastProcessed = (((this.getType() == null) || this.getType().isInstance(this.lastProcessed)) ? this.lastProcessed
				: ObjectMapperHelper.convert(BatchExecutor.OBJECT_MAPPER, this.lastProcessed, this.getType(), false));
		return this.lastProcessed;
	}

	/**
	 * Sets the lastProcessed.
	 *
	 * @param lastProcessed New lastProcessed.
	 */
	public void setLastProcessed(
			final Type lastProcessed) {
		this.lastProcessed = lastProcessed;
	}

	/**
	 * Gets the delayBetweenRuns.
	 *
	 * @return The delayBetweenRuns.
	 */
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public Duration getDelayBetweenRuns() {
		this.delayBetweenRuns = (this.delayBetweenRuns == null ? Duration.ofSeconds(2) : this.delayBetweenRuns);
		return this.delayBetweenRuns;
	}

	/**
	 * Sets the delayBetweenRuns.
	 *
	 * @param delayBetweenRuns New delayBetweenRuns.
	 */
	public void setDelayBetweenRuns(
			final Duration delayBetweenRuns) {
		this.delayBetweenRuns = delayBetweenRuns;
	}

	/**
	 * Gets the finishWithin.
	 *
	 * @return The finishWithin.
	 */
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public Duration getFinishWithin() {
		this.finishWithin = (this.finishWithin == null ? Duration.ofDays(1) : this.finishWithin);
		return this.finishWithin;
	}

	/**
	 * Sets the finishWithin.
	 *
	 * @param finishWithin New finishWithin.
	 */
	public void setFinishWithin(
			final Duration finishWithin) {
		this.finishWithin = finishWithin;
	}

	/**
	 * Gets the cleansWithin.
	 *
	 * @return The cleansWithin.
	 */
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public Duration getCleansWithin() {
		this.cleansWithin = (this.cleansWithin == null ? this.getFinishWithin().multipliedBy(5) : this.cleansWithin);
		return this.cleansWithin;
	}

	/**
	 * Sets the cleansWithin.
	 *
	 * @param cleansWithin New cleansWithin.
	 */
	public void setCleansWithin(
			final Duration cleansWithin) {
		this.cleansWithin = cleansWithin;
	}

	/**
	 * Gets the arguments.
	 *
	 * @return The arguments.
	 */
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public Map<String, String> getArguments() {
		this.arguments = (this.arguments == null ? new HashMap<>() : this.arguments);
		return this.arguments;
	}

	/**
	 * Sets the arguments.
	 *
	 * @param arguments New arguments.
	 */
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public void setArguments(
			final Map<String, String> getArguments) {
		this.arguments = getArguments;
	}

	/**
	 * Gets the actionBeanName.
	 *
	 * @return The actionBeanName.
	 */
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public String getActionBeanName() {
		return this.actionBeanName;
	}

	/**
	 * Sets the actionBeanName.
	 *
	 * @param actionBeanName New actionBeanName.
	 */
	public void setActionBeanName(
			final String actionBeanName) {
		this.actionBeanName = actionBeanName;
	}

	/**
	 * Gets the actionDelegateMethods.
	 *
	 * @return The actionDelegateMethods.
	 */
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public Map<BatchAction, String> getActionDelegateMethods() {
		this.actionDelegateMethods = (this.actionDelegateMethods == null
				? new HashMap<>(Map.of(BatchAction.START, "start", BatchAction.RESUME, "resume", BatchAction.GET, "get", BatchAction.EXECUTE, "execute",
						BatchAction.FINISH, "finish"))
				: this.actionDelegateMethods);
		return this.actionDelegateMethods;
	}

	/**
	 * Sets the actionDelegateMethods.
	 *
	 * @param actionDelegateMethods New actionDelegateMethods.
	 */
	public void setActionDelegateMethods(
			final Map<BatchAction, String> actionDelegateMethods) {
		this.actionDelegateMethods = actionDelegateMethods;
	}

	/**
	 * Executes a delegate method.
	 *
	 * @param  action            Action.
	 * @param  arguments         Arguments.
	 * @return                   The object return.
	 * @throws BusinessException If the method fails.
	 */
	public Object executeActionDelegateMethod(
			final BatchAction action,
			final Object... arguments) throws BusinessException {
		Object returnObject = null;
		String beanName = this.getActionBeanName();
		String methodName = this.getActionDelegateMethods().get(action);
		if (StringUtils.isNotBlank(methodName)) {
			final String[] methodPath = methodName.split(".");
			if (methodPath.length > 1) {
				beanName = methodPath[0];
				methodName = methodPath[1];
			}
			try {
				final Object bean = StaticContextAccessor.getBean(beanName);
				returnObject = MethodUtils.invokeMethod(bean, methodName, arguments);
			}
			catch (final IntegrationException exception) {
				throw exception;
			}
			catch (final Exception exception) {
				throw new IntegrationException(new SimpleMessage("batch.action.error"), exception);
			}
		}
		return returnObject;
	}

	/**
	 * Gets the messagesTemplates.
	 *
	 * @return The messagesTemplates.
	 */
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public Map<BatchAction, String> getMessagesTemplates() {
		this.messagesTemplates = (this.messagesTemplates == null
				? new HashMap<>(Map.of(BatchAction.START, "Starting batch for '${key}'.", BatchAction.RESUME,
						"Resuming batch for '${key}' from object '${lastProcessed}'.", BatchAction.FINISH,
						"Finishing batch for '${key}' at object '${lastProcessed}' in '${duration}' minutes."))
				: this.messagesTemplates);
		return this.messagesTemplates;
	}

	/**
	 * Sets the messagesTemplates.
	 *
	 * @param messagesTemplates New messagesTemplates.
	 */
	public void setMessagesTemplates(
			final Map<BatchAction, String> messagesTemplates) {
		this.messagesTemplates = messagesTemplates;
	}

	/**
	 * Gets the slackChannels.
	 *
	 * @return The slackChannels.
	 */
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public Map<BatchAction, String> getSlackChannels() {
		this.slackChannels = (this.slackChannels == null ? new HashMap<>() : this.slackChannels);
		return this.slackChannels;
	}

	/**
	 * Sets the slackChannels.
	 *
	 * @param slackChannels New slackChannels.
	 */
	public void setSlackChannels(
			final Map<BatchAction, String> slackChannels) {
		this.slackChannels = slackChannels;
	}

	/**
	 * Gets the lastStartedAt.
	 *
	 * @return The lastStartedAt.
	 */
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public LocalDateTime getLastStartedAt() {
		this.lastStartedAt = (this.lastStartedAt == null ? DateTimeHelper.getCurrentLocalDateTime() : this.lastStartedAt);
		return this.lastStartedAt;
	}

	/**
	 * Sets the lastStartedAt.
	 *
	 * @param lastStartedAt New lastStartedAt.
	 */
	public void setLastStartedAt(
			final LocalDateTime lastStartedAt) {
		this.lastStartedAt = lastStartedAt;
	}

	/**
	 * Gets the lastFinishedAt.
	 *
	 * @return The lastFinishedAt.
	 */
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public LocalDateTime getLastFinishedAt() {
		return this.lastFinishedAt;
	}

	/**
	 * If the batch is finished.
	 *
	 * @return If the batch is finished.
	 */
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public Boolean isFinished() {
		return (this.getLastFinishedAt() != null) && (this.getLastStartedAt() != null) && this.getLastFinishedAt().isAfter(this.getLastStartedAt());
	}

	/**
	 * Sets the lastFinishedAt.
	 *
	 * @param lastFinishedAt New lastFinishedAt.
	 */
	public void setLastFinishedAt(
			final LocalDateTime lastFinishedAt) {
		this.lastFinishedAt = lastFinishedAt;
	}

	/**
	 * Gets the cancelledAt.
	 *
	 * @return The cancelledAt.
	 */
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public LocalDateTime getLastCancelledAt() {
		return this.lastCancelledAt;
	}

	/**
	 * Sets the cancelledAt.
	 *
	 * @param cancelledAt New cancelledAt.
	 */
	public void setLastCancelledAt(
			final LocalDateTime cancelledAt) {
		this.lastCancelledAt = cancelledAt;
	}

	/**
	 * Gets the expiredAt.
	 *
	 * @return The expiredAt.
	 */
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public LocalDateTime getExpiredAt() {
		return (this.getLastCancelledAt() == null ? this.getLastStartedAt().plus(this.getFinishWithin()) : this.getLastCancelledAt());
	}

	/**
	 * If the record is expired.
	 *
	 * @return If the record is expired.
	 */
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public Boolean isExpired() {
		return DateTimeHelper.getCurrentLocalDateTime().isAfter(this.getExpiredAt());
	}

	/**
	 * Gets the lastProcessedCount.
	 *
	 * @return The lastProcessedCount.
	 */
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public Long getLastProcessedCount() {
		this.lastProcessedCount = (this.lastProcessedCount == null ? 0 : this.lastProcessedCount);
		return this.lastProcessedCount;
	}

	/**
	 * Sets the lastProcessedCount.
	 *
	 * @param lastProcessedCount New lastProcessedCount.
	 */
	public void setLastProcessedCount(
			final Long lastProcessedCount) {
		this.lastProcessedCount = lastProcessedCount;
	}

	/**
	 * Gets the keptUntil.
	 *
	 * @return The keptUntil.
	 */
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public LocalDateTime getKeptUntil() {
		return this.getLastStartedAt().plus(this.getCleansWithin());
	}

	/**
	 * If the record should be cleaned.
	 *
	 * @return If the record should be cleaned.
	 */
	public Boolean shouldBeCleaned() {
		return DateTimeHelper.getCurrentLocalDateTime().isAfter(this.getKeptUntil());
	}

	/**
	 * Resets the batch record.
	 */
	public void reset() {
		this.setLastStartedAt(null);
		this.setLastCancelledAt(null);
		this.setLastProcessed(null);
		this.setLastProcessedCount(null);
		this.getLastStartedAt();
		this.getLastProcessedCount();
	}

	/**
	 * @see org.coldis.library.model.Typable#getTypeName()
	 */
	@Override
	@JsonView({ ModelView.Persistent.class, ModelView.Public.class })
	public String getTypeName() {
		return BatchExecutor.TYPE_NAME;
	}

	/**
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		return Objects.hash(this.actionBeanName, this.actionDelegateMethods, this.arguments, this.cleansWithin, this.delayBetweenRuns, this.finishWithin,
				this.itemTypeName, this.keySuffix, this.lastCancelledAt, this.lastFinishedAt, this.lastProcessed, this.lastProcessedCount, this.lastStartedAt,
				this.messagesTemplates, this.size, this.slackChannels);
	}

	/**
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	@Override
	public boolean equals(
			final Object obj) {
		if (this == obj) {
			return true;
		}
		if ((obj == null) || (this.getClass() != obj.getClass())) {
			return false;
		}
		final BatchExecutor other = (BatchExecutor) obj;
		return Objects.equals(this.actionBeanName, other.actionBeanName) && Objects.equals(this.actionDelegateMethods, other.actionDelegateMethods)
				&& Objects.equals(this.arguments, other.arguments) && Objects.equals(this.cleansWithin, other.cleansWithin)
				&& Objects.equals(this.delayBetweenRuns, other.delayBetweenRuns) && Objects.equals(this.finishWithin, other.finishWithin)
				&& Objects.equals(this.itemTypeName, other.itemTypeName) && Objects.equals(this.keySuffix, other.keySuffix)
				&& Objects.equals(this.lastCancelledAt, other.lastCancelledAt) && Objects.equals(this.lastFinishedAt, other.lastFinishedAt)
				&& Objects.equals(this.lastProcessed, other.lastProcessed) && Objects.equals(this.lastProcessedCount, other.lastProcessedCount)
				&& Objects.equals(this.lastStartedAt, other.lastStartedAt) && Objects.equals(this.messagesTemplates, other.messagesTemplates)
				&& Objects.equals(this.size, other.size) && Objects.equals(this.slackChannels, other.slackChannels);
	}

	/**
	 * Starts the batch.
	 *
	 * @throws BusinessException If start fails.
	 */
	public void start() throws BusinessException {
		this.executeActionDelegateMethod(BatchAction.START);
	}

	/**
	 * Resumes the batch.
	 *
	 * @throws BusinessException If resume fails.
	 */
	public void resume() throws BusinessException {
		this.executeActionDelegateMethod(BatchAction.RESUME);
	}

	/**
	 * Gets the next batch to be processed.
	 *
	 * @return                   The next batch to be processed.
	 * @throws BusinessException If the next to process cannot be retrieved.
	 */
	@SuppressWarnings("unchecked")
	public List<Type> get() throws BusinessException {
		return (List<Type>) this.executeActionDelegateMethod(BatchAction.GET, this.getLastProcessed(), this.getSize(), this.getArguments());
	}

	/**
	 * Finishes the batch.
	 *
	 * @throws BusinessException If finish fails.
	 */
	public void finish() throws BusinessException {
		this.executeActionDelegateMethod(BatchAction.FINISH);
	}

	/**
	 * Executes the batch for one item.
	 *
	 * @param  object            Object.
	 * @throws BusinessException If execution fails.
	 */
	public void execute(
			final Type object) throws BusinessException {
		this.executeActionDelegateMethod(BatchAction.EXECUTE, object);
	}

}
