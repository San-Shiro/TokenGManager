package store

import (
	"context"
	"errors"
	"time"

	"github.com/San-Shiro/tokeng-server/internal/model"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
)

var (
	ErrUserAlreadyExists = errors.New("a user with this email already exists")
	ErrUserNotFound      = errors.New("user not found")
)

type UserStore struct {
	pool *pgxpool.Pool
}

func NewUserStore(pool *pgxpool.Pool) *UserStore {
	return &UserStore{pool: pool}
}

// CreateUser inserts a new user into tokeng.users
func (s *UserStore) CreateUser(ctx context.Context, email, passwordHash string) (string, error) {
	query := `
		INSERT INTO tokeng.users (email, password_hash)
		VALUES ($1, $2)
		RETURNING id;
	`
	var id string
	err := s.pool.QueryRow(ctx, query, email, passwordHash).Scan(&id)
	if err != nil {
		var pgErr *pgconn.PgError
		if errors.As(err, &pgErr) && pgErr.Code == "23505" { // unique_violation
			return "", ErrUserAlreadyExists
		}
		return "", err
	}
	return id, nil
}

// GetUserByEmail queries a user by email
func (s *UserStore) GetUserByEmail(ctx context.Context, email string) (*model.User, error) {
	query := `
		SELECT id, email, password_hash, created_at, last_login_at
		FROM tokeng.users
		WHERE email = $1;
	`
	var u model.User
	err := s.pool.QueryRow(ctx, query, email).Scan(
		&u.ID,
		&u.Email,
		&u.PasswordHash,
		&u.CreatedAt,
		&u.LastLoginAt,
	)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrUserNotFound
		}
		return nil, err
	}
	return &u, nil
}

// UpdateLastLogin updates the last login timestamp for the given user ID
func (s *UserStore) UpdateLastLogin(ctx context.Context, userID string) error {
	query := `
		UPDATE tokeng.users
		SET last_login_at = $1
		WHERE id = $2;
	`
	_, err := s.pool.Exec(ctx, query, time.Now(), userID)
	return err
}
