package store

import (
	"context"
	"time"

	"github.com/San-Shiro/tokeng-server/internal/model"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type InstanceStore struct {
	pool *pgxpool.Pool
}

func NewInstanceStore(pool *pgxpool.Pool) *InstanceStore {
	return &InstanceStore{pool: pool}
}

// PullDelta fetches all instances modified since the given timestamp for a specific user
func (s *InstanceStore) PullDelta(ctx context.Context, userID string, since time.Time) ([]model.TokenInstance, error) {
	query := `
		SELECT instance_id, user_id, email, master_token, aas_token, sid, lsid,
		       android_id, gsf_id, security_token, device_name, device_model, device_brand,
		       device_fingerprint, device_sdk, account_status, last_validated_at,
		       last_validation_result, signed_out_reason, deleted, created_at, updated_at
		FROM tokeng.token_instances
		WHERE user_id = $1 AND updated_at > $2
		ORDER BY updated_at ASC
		LIMIT 2000;
	`
	rows, err := s.pool.Query(ctx, query, userID, since)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	instances := make([]model.TokenInstance, 0)
	for rows.Next() {
		var ti model.TokenInstance
		err := rows.Scan(
			&ti.InstanceID,
			&ti.UserID,
			&ti.Email,
			&ti.MasterToken,
			&ti.AasToken,
			&ti.Sid,
			&ti.Lsid,
			&ti.AndroidID,
			&ti.GsfID,
			&ti.SecurityToken,
			&ti.DeviceName,
			&ti.DeviceModel,
			&ti.DeviceBrand,
			&ti.DeviceFingerprint,
			&ti.DeviceSDK,
			&ti.AccountStatus,
			&ti.LastValidatedAt,
			&ti.LastValidationResult,
			&ti.SignedOutReason,
			&ti.Deleted,
			&ti.CreatedAt,
			&ti.UpdatedAt,
		)
		if err != nil {
			return nil, err
		}
		instances = append(instances, ti)
	}

	return instances, rows.Err()
}

// PushDelta upserts a batch of instances for the authenticated user
func (s *InstanceStore) PushDelta(ctx context.Context, userID string, instances []model.TokenInstance) (int, int, error) {
	if len(instances) == 0 {
		return 0, 0, nil
	}

	tx, err := s.pool.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return 0, 0, err
	}
	defer tx.Rollback(ctx)

	upsertSQL := `
		INSERT INTO tokeng.token_instances (
			instance_id, user_id, email, master_token, aas_token, sid, lsid,
			android_id, gsf_id, security_token, device_name, device_model, device_brand,
			device_fingerprint, device_sdk, account_status, last_validated_at,
			last_validation_result, signed_out_reason, deleted, updated_at
		) VALUES (
			$1, $2, $3, $4, $5, $6, $7,
			$8, $9, $10, $11, $12, $13,
			$14, $15, $16, $17,
			$18, $19, $20, now()
		)
		ON CONFLICT (user_id, instance_id) DO UPDATE SET
			master_token = EXCLUDED.master_token,
			aas_token = EXCLUDED.aas_token,
			sid = EXCLUDED.sid,
			lsid = EXCLUDED.lsid,
			android_id = EXCLUDED.android_id,
			gsf_id = EXCLUDED.gsf_id,
			security_token = EXCLUDED.security_token,
			device_name = EXCLUDED.device_name,
			device_model = EXCLUDED.device_model,
			device_brand = EXCLUDED.device_brand,
			device_fingerprint = EXCLUDED.device_fingerprint,
			device_sdk = EXCLUDED.device_sdk,
			account_status = EXCLUDED.account_status,
			last_validated_at = EXCLUDED.last_validated_at,
			last_validation_result = EXCLUDED.last_validation_result,
			signed_out_reason = EXCLUDED.signed_out_reason,
			deleted = EXCLUDED.deleted,
			updated_at = now()
		WHERE tokeng.token_instances.updated_at <= EXCLUDED.updated_at;
	`

	accepted := 0
	skippedStale := 0

	for _, inst := range instances {
		cmdTag, err := tx.Exec(ctx, upsertSQL,
			inst.InstanceID,
			userID, // strictly enforced from token
			inst.Email,
			inst.MasterToken,
			inst.AasToken,
			inst.Sid,
			inst.Lsid,
			inst.AndroidID,
			inst.GsfID,
			inst.SecurityToken,
			inst.DeviceName,
			inst.DeviceModel,
			inst.DeviceBrand,
			inst.DeviceFingerprint,
			inst.DeviceSDK,
			inst.AccountStatus,
			inst.LastValidatedAt,
			inst.LastValidationResult,
			inst.SignedOutReason,
			inst.Deleted,
		)
		if err != nil {
			return 0, 0, err
		}
		if cmdTag.RowsAffected() > 0 {
			accepted++
		} else {
			skippedStale++
		}
	}

	if err := tx.Commit(ctx); err != nil {
		return 0, 0, err
	}

	return accepted, skippedStale, nil
}

// GetStats returns global metrics across the database
func (s *InstanceStore) GetStats(ctx context.Context) (*model.GlobalStatsResponse, error) {
	query := `
		SELECT
			(SELECT count(*) FROM tokeng.users),
			(SELECT count(*) FROM tokeng.token_instances WHERE NOT deleted),
			(SELECT count(*) FROM tokeng.token_instances WHERE NOT deleted AND account_status = 'ACTIVE'),
			(SELECT count(*) FROM tokeng.token_instances WHERE NOT deleted AND account_status = 'EXPIRED'),
			(SELECT count(DISTINCT email) FROM tokeng.token_instances WHERE NOT deleted);
	`
	var stats model.GlobalStatsResponse
	err := s.pool.QueryRow(ctx, query).Scan(
		&stats.TotalUsers,
		&stats.TotalInstances,
		&stats.ActiveTokens,
		&stats.ExpiredTokens,
		&stats.DistinctGmails,
	)
	if err != nil {
		return nil, err
	}
	stats.GeneratedAt = time.Now()
	return &stats, nil
}
