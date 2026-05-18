# Test Patterns — 各语言测试框架模板与最佳实践

## Python — pytest

### 基本结构

```python
import pytest
from unittest.mock import patch, MagicMock

# 被测模块
from myapp.services.user import UserService


class TestUserService:
    """UserService 单元测试"""

    def setup_method(self):
        """每个测试前初始化"""
        self.service = UserService()

    def test_get_user_success(self):
        """正常路径：成功获取用户"""
        user = self.service.get_user(user_id=1)
        assert user is not None
        assert user.id == 1

    def test_get_user_not_found(self):
        """边界条件：用户不存在"""
        with pytest.raises(UserNotFoundError):
            self.service.get_user(user_id=99999)

    def test_get_user_invalid_id(self):
        """边界条件：无效 ID"""
        with pytest.raises(ValueError):
            self.service.get_user(user_id=-1)

    def test_get_user_with_none_id(self):
        """边界条件：None 值"""
        with pytest.raises(TypeError):
            self.service.get_user(user_id=None)

    @patch("myapp.services.user.database")
    def test_get_user_db_error(self, mock_db):
        """异常路径：数据库错误"""
        mock_db.query.side_effect = DatabaseError("Connection failed")
        with pytest.raises(ServiceError):
            self.service.get_user(user_id=1)
```

### Mock 外部依赖

```python
# Mock 数据库
@patch("myapp.db.session")
def test_create_user(self, mock_session):
    mock_session.add.return_value = None
    mock_session.commit.return_value = None
    result = self.service.create_user(name="Alice", email="alice@example.com")
    assert result.name == "Alice"
    mock_session.add.assert_called_once()

# Mock HTTP 请求
@patch("requests.get")
def test_fetch_external_data(self, mock_get):
    mock_get.return_value = MagicMock(
        status_code=200,
        json=lambda: {"data": "value"}
    )
    result = self.service.fetch_data(url="https://api.example.com")
    assert result == {"data": "value"}

# Mock 文件系统
@patch("builtins.open", create=True)
def test_read_config(self, mock_open):
    mock_open.return_value.__enter__ = lambda s: s
    mock_open.return_value.__exit__ = MagicMock(return_value=False)
    mock_open.return_value.read.return_value = '{"key": "value"}'
    config = self.service.read_config("/path/to/config.json")
    assert config["key"] == "value"
```

### Fixtures

```python
@pytest.fixture
def user_service():
    """共享 fixture"""
    service = UserService(db_url="sqlite:///:memory:")
    yield service
    service.cleanup()

@pytest.fixture
def sample_user():
    return {"id": 1, "name": "Alice", "email": "alice@example.com"}

def test_update_user(user_service, sample_user):
    user_service.create_user(**sample_user)
    result = user_service.update_user(1, name="Bob")
    assert result.name == "Bob"
```

---

## Python — unittest

```python
import unittest
from unittest.mock import patch, MagicMock


class TestUserService(unittest.TestCase):

    def setUp(self):
        self.service = UserService()

    def test_get_user_success(self):
        user = self.service.get_user(user_id=1)
        self.assertIsNotNone(user)
        self.assertEqual(user.id, 1)

    def test_get_user_not_found(self):
        self.assertRaises(UserNotFoundError, self.service.get_user, user_id=99999)

    def tearDown(self):
        self.service.cleanup()


if __name__ == "__main__":
    unittest.main()
```

---

## JavaScript — Jest

### 基本结构

```javascript
// user.service.test.js
const { UserService } = require('./user.service');

describe('UserService', () => {
  let service;

  beforeEach(() => {
    service = new UserService();
    jest.clearAllMocks();
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  describe('getUser', () => {
    test('should return user when found', async () => {
      const user = await service.getUser(1);
      expect(user).toBeDefined();
      expect(user.id).toBe(1);
    });

    test('should throw UserNotFoundError when user does not exist', async () => {
      await expect(service.getUser(99999)).rejects.toThrow('User not found');
    });

    test('should throw TypeError when id is null', async () => {
      await expect(service.getUser(null)).rejects.toThrow(TypeError);
    });
  });
});
```

### Mock 外部依赖

```javascript
// Mock 模块
jest.mock('../database', () => ({
  query: jest.fn(),
}));

const db = require('../database');

test('should handle database error', async () => {
  db.query.mockRejectedValue(new Error('Connection failed'));
  await expect(service.getUser(1)).rejects.toThrow('Database error');
});

// Mock fetch
global.fetch = jest.fn(() =>
  Promise.resolve({
    ok: true,
    json: () => Promise.resolve({ data: 'value' }),
  })
);

test('should fetch external data', async () => {
  const result = await service.fetchData('https://api.example.com');
  expect(result).toEqual({ data: 'value' });
  expect(fetch).toHaveBeenCalledWith('https://api.example.com');
});
```

---

## TypeScript — Jest + ts-jest

```typescript
// user.service.spec.ts
import { UserService } from './user.service';
import { UserNotFoundError } from './errors';

jest.mock('../database');
import { database } from '../database';

describe('UserService', () => {
  let service: UserService;

  beforeEach(() => {
    service = new UserService();
    jest.clearAllMocks();
  });

  it('should return user when found', async () => {
    const user = await service.getUser(1);
    expect(user).toBeDefined();
    expect(user.id).toBe(1);
  });

  it('should throw UserNotFoundError when user does not exist', async () => {
    await expect(service.getUser(99999)).rejects.toThrow(UserNotFoundError);
  });

  it('should handle database errors gracefully', async () => {
    (database.query as jest.Mock).mockRejectedValue(new Error('DB error'));
    await expect(service.getUser(1)).rejects.toThrow('Service error');
  });
});
```

---

## Go — testing package

```go
// user_service_test.go
package user

import (
    "testing"
    "errors"
)

func TestGetUser_Success(t *testing.T) {
    service := NewUserService(mockDB)
    user, err := service.GetUser(1)
    if err != nil {
        t.Fatalf("expected no error, got %v", err)
    }
    if user.ID != 1 {
        t.Errorf("expected user ID 1, got %d", user.ID)
    }
}

func TestGetUser_NotFound(t *testing.T) {
    service := NewUserService(mockDB)
    _, err := service.GetUser(99999)
    if !errors.Is(err, ErrUserNotFound) {
        t.Errorf("expected ErrUserNotFound, got %v", err)
    }
}

func TestGetUser_InvalidID(t *testing.T) {
    service := NewUserService(mockDB)
    _, err := service.GetUser(-1)
    if err == nil {
        t.Error("expected error for negative ID, got nil")
    }
}

// Table-driven tests
func TestGetUser_TableDriven(t *testing.T) {
    tests := []struct {
        name    string
        userID  int
        wantErr bool
    }{
        {"valid user", 1, false},
        {"user not found", 99999, true},
        {"negative id", -1, true},
        {"zero id", 0, true},
    }

    service := NewUserService(mockDB)
    for _, tt := range tests {
        t.Run(tt.name, func(t *testing.T) {
            _, err := service.GetUser(tt.userID)
            if (err != nil) != tt.wantErr {
                t.Errorf("GetUser(%d) error = %v, wantErr %v", tt.userID, err, tt.wantErr)
            }
        })
    }
}
```

---

## 通用最佳实践

### 测试命名规范

| 语言 | 格式 | 示例 |
|------|------|------|
| Python | `test_<func>_<scenario>` | `test_get_user_not_found` |
| JS/TS | `should <behavior> when <condition>` | `should throw error when user not found` |
| Go | `Test<Func>_<Scenario>` | `TestGetUser_NotFound` |

### 覆盖率目标

- 新增业务逻辑代码：≥ 80% 行覆盖率
- 安全相关代码：≥ 95% 行覆盖率
- 工具函数：≥ 90% 行覆盖率

### 必须覆盖的场景

1. ✅ 正常路径（happy path）
2. ✅ 空值/零值/None 输入
3. ✅ 边界值（最大、最小）
4. ✅ 错误/异常路径
5. ✅ 并发场景（如适用）

### Mock 原则

1. **只 mock 外部依赖**：数据库、HTTP、文件系统、时间
2. **不 mock 被测代码本身**
3. **验证 mock 调用**：确认依赖被正确调用（参数、次数）
4. **每次测试重置 mock**：避免测试间互相影响
