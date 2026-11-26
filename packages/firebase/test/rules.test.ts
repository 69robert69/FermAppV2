import { readFileSync } from 'node:fs';
import { assertFails, assertSucceeds, initializeTestEnvironment, type RulesTestEnvironment } from '@firebase/rules-unit-testing';
import { collection, deleteDoc, doc, getDoc, getDocs, query, runTransaction, setDoc, updateDoc, where, writeBatch } from 'firebase/firestore';
import { afterAll, beforeAll, beforeEach, describe, it } from 'vitest';

let env: RulesTestEnvironment;

const users = {
  admin: { uid: 'admin', role: 'ADMIN', farmId: 'farmA' },
  manager: { uid: 'manager', role: 'FARM_MANAGER', farmId: 'farmA' },
  operator: { uid: 'operator', role: 'OPERATOR', farmId: 'farmA' },
  agronomist: { uid: 'agronomist', role: 'AGRONOMIST', farmId: 'farmA' },
  stranger: { uid: 'stranger', role: 'ADMIN', farmId: 'farmB' },
};

const db = (uid: string, token: Record<string, unknown> = {}) => env.authenticatedContext(uid, token).firestore();

beforeAll(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-fermapp',
    firestore: { rules: readFileSync('firestore.rules', 'utf8'), host: '127.0.0.1', port: 8180 },
  });
});

afterAll(() => env.cleanup());

beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async (context) => {
    const admin = context.firestore();
    for (const user of Object.values(users)) await setDoc(doc(admin, 'users', user.uid), { ...user, name: user.uid });
    await setDoc(doc(admin, 'farms', 'farmA'), { ownerId: 'admin', name: 'Farm A' });
    await setDoc(doc(admin, 'parcels', 'p1'), { farmId: 'farmA', ownerId: 'admin', name: 'Sud' });
    await setDoc(doc(admin, 'tasks', 't1'), { farmId: 'farmA', title: 'Task', status: 'TODO', responsibleUser: 'operator' });
    await setDoc(doc(admin, 'tasks', 't2'), { farmId: 'farmA', title: 'Altul', status: 'TODO', responsibleUser: 'manager' });
    await setDoc(doc(admin, 'reports', 'r1'), { farmId: 'farmA', expectedProfitEurHa: 100 });
    await setDoc(doc(admin, 'expenses', 'e1'), { farmId: 'farmA', amount: 100 });
    await setDoc(doc(admin, 'machineWork', 'w1'), { farmId: 'farmA', operatorId: 'manager', status: 'RUNNING' });
    await setDoc(doc(admin, 'notifications', 'n1'), { farmId: 'farmA', userId: 'manager', title: 'Salut' });
    await setDoc(doc(admin, 'invites', 'nou@farm.test'), { farmId: 'farmA', role: 'OPERATOR' });
  });
});

describe('farm isolation', () => {
  it('allows reading the data of one’s own farm', async () => {
    await assertSucceeds(getDoc(doc(db('operator'), 'parcels', 'p1')));
  });

  it('forbids reading and changing another farm’s data', async () => {
    await assertFails(getDoc(doc(db('stranger'), 'parcels', 'p1')));
    await assertFails(updateDoc(doc(db('stranger'), 'parcels', 'p1'), { name: 'Furat' }));
    await assertFails(getDoc(doc(db('stranger'), 'users', 'admin')));
  });

  it('forbids creating documents in another farm', async () => {
    await assertFails(setDoc(doc(db('stranger'), 'parcels', 'p2'), { farmId: 'farmA', ownerId: 'stranger' }));
  });

  it('forbids moving a document to another farm', async () => {
    await assertFails(updateDoc(doc(db('admin'), 'parcels', 'p1'), { farmId: 'farmB' }));
  });

  it('forbids access without authentication', async () => {
    await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), 'parcels', 'p1')));
  });

  it('forbids unknown collections', async () => {
    await assertFails(setDoc(doc(db('admin'), 'secrete', 'x'), { farmId: 'farmA' }));
  });
});

describe('roles', () => {
  it('does not let a user change their own role or farm', async () => {
    await assertFails(updateDoc(doc(db('operator'), 'users', 'operator'), { role: 'ADMIN' }));
    await assertFails(updateDoc(doc(db('operator'), 'users', 'operator'), { farmId: 'farmB' }));
    await assertSucceeds(updateDoc(doc(db('operator'), 'users', 'operator'), { name: 'Ion' }));
  });

  it('lets the administrator change a member’s role', async () => {
    await assertSucceeds(updateDoc(doc(db('admin'), 'users', 'operator'), { role: 'AGRONOMIST' }));
    await assertFails(updateDoc(doc(db('manager'), 'users', 'operator'), { role: 'ADMIN' }));
  });

  it('does not let the operator or agronomist create parcels', async () => {
    await assertFails(setDoc(doc(db('operator'), 'parcels', 'p3'), { farmId: 'farmA', ownerId: 'operator' }));
    await assertFails(setDoc(doc(db('agronomist'), 'parcels', 'p3'), { farmId: 'farmA', ownerId: 'agronomist' }));
    await assertSucceeds(setDoc(doc(db('manager'), 'parcels', 'p3'), { farmId: 'farmA', ownerId: 'manager' }));
  });

  it('allows the agronomist only the parcel planning fields', async () => {
    await assertSucceeds(updateDoc(doc(db('agronomist'), 'parcels', 'p1'), { plannedCrop: 'SOY', rotationScore: 90 }));
    await assertFails(updateDoc(doc(db('agronomist'), 'parcels', 'p1'), { name: 'Schimbat' }));
  });

  it('allows the operator only the status of their own tasks', async () => {
    await assertSucceeds(updateDoc(doc(db('operator'), 'tasks', 't1'), { status: 'DONE' }));
    await assertFails(updateDoc(doc(db('operator'), 'tasks', 't1'), { title: 'Schimbat' }));
    await assertFails(updateDoc(doc(db('operator'), 'tasks', 't2'), { status: 'DONE' }));
  });

  it('hides financial data from operators', async () => {
    await assertFails(getDoc(doc(db('operator'), 'reports', 'r1')));
    await assertFails(getDoc(doc(db('operator'), 'expenses', 'e1')));
    await assertSucceeds(getDoc(doc(db('agronomist'), 'reports', 'r1')));
  });

  it('lets the operator control only their own operations', async () => {
    await assertFails(updateDoc(doc(db('operator'), 'machineWork', 'w1'), { status: 'PAUSED' }));
    await assertFails(setDoc(doc(db('operator'), 'machineWork', 'w2'), { farmId: 'farmA', operatorId: 'manager' }));
    await assertSucceeds(setDoc(doc(db('operator'), 'machineWork', 'w3'), { farmId: 'farmA', operatorId: 'operator' }));
  });
});

describe('lists used by the app', () => {
  const farmList = (uid: string, name: string, farmId = 'farmA') =>
    getDocs(query(collection(db(uid), name), where('farmId', '==', farmId)));

  it('allows listing the collections of one’s own farm', async () => {
    for (const name of ['users', 'parcels', 'cropHistory', 'tasks', 'machines', 'machineWork', 'irrigations', 'cropPlans', 'observations']) {
      await assertSucceeds(farmList('operator', name));
    }
  });

  it('forbids listing without the farm filter or for another farm', async () => {
    await assertFails(getDocs(collection(db('admin'), 'parcels')));
    await assertFails(farmList('stranger', 'parcels'));
  });

  it('lists reports only for planners', async () => {
    await assertSucceeds(farmList('manager', 'reports'));
    await assertFails(farmList('operator', 'reports'));
  });

  it('lists only one’s own notifications', async () => {
    const own = query(collection(db('manager'), 'notifications'), where('farmId', '==', 'farmA'), where('userId', '==', 'manager'));
    await assertSucceeds(getDocs(own));
    await assertFails(farmList('manager', 'notifications'));
  });

  it('saves the complete plan of a parcel in a single operation', async () => {
    const firestore = db('agronomist');
    const batch = writeBatch(firestore);
    batch.set(doc(firestore, 'cropHistory', 'p1_2027'), { farmId: 'farmA', parcelId: 'p1', year: 2027, crop: 'CORN' }, { merge: true });
    batch.set(doc(firestore, 'cropPlans', 'plan1'), { farmId: 'farmA', parcelId: 'p1', active: true }, { merge: true });
    batch.set(doc(firestore, 'reports', 'plan1'), { farmId: 'farmA', parcelId: 'p1', expectedProfitEurHa: 10 }, { merge: true });
    batch.set(doc(firestore, 'tasks', 'plan1_SOWING'), { farmId: 'farmA', parcelId: 'p1', status: 'TODO' }, { merge: true });
    batch.update(doc(firestore, 'parcels', 'p1'), { plannedCrop: 'CORN', rotationScore: 80 });
    await assertSucceeds(batch.commit());
  });
});

describe('currency migration', () => {
  it('lets a manager check and claim the farm’s missing marker in a transaction', async () => {
    const firestore = db('manager');
    const marker = doc(firestore, 'economics', 'farmA__currency');
    await assertSucceeds(
      runTransaction(firestore, async (transaction) => {
        const existing = await transaction.get(marker);
        if (!existing.exists()) transaction.set(marker, { farmId: 'farmA', kind: 'currency', currency: 'EUR' });
      }),
    );
    await assertSucceeds(getDoc(marker));
  });

  it('does not reveal other farms’ markers or let operators check them', async () => {
    await assertFails(getDoc(doc(db('stranger'), 'economics', 'farmA__currency')));
    await assertFails(getDoc(doc(db('operator'), 'economics', 'farmA__currency')));
    await assertFails(getDoc(doc(db('manager'), 'economics', 'farmA__other')));
  });
});

describe('catalog', () => {
  it('is visible to the whole farm and editable only by the administrator', async () => {
    await assertSucceeds(setDoc(doc(db('admin'), 'seedCatalog', 's1'), { farmId: 'farmA', name: 'P0710' }));
    await assertSucceeds(getDoc(doc(db('operator'), 'seedCatalog', 's1')));
    await assertFails(updateDoc(doc(db('manager'), 'seedCatalog', 's1'), { name: 'Altul' }));
    await assertFails(setDoc(doc(db('agronomist'), 'machineCatalog', 'm1'), { farmId: 'farmA', model: '8R' }));
    await assertFails(deleteDoc(doc(db('operator'), 'seedCatalog', 's1')));
  });

  it('cannot be read or written by another farm', async () => {
    await assertSucceeds(setDoc(doc(db('admin'), 'machineCatalog', 'm2'), { farmId: 'farmA', model: '8R' }));
    await assertFails(getDoc(doc(db('stranger'), 'machineCatalog', 'm2')));
    await assertFails(setDoc(doc(db('stranger'), 'implementCatalog', 'i1'), { farmId: 'farmA' }));
  });
});

describe('notifications', () => {
  it('are visible only to the recipient', async () => {
    await assertSucceeds(getDoc(doc(db('manager'), 'notifications', 'n1')));
    await assertFails(getDoc(doc(db('admin'), 'notifications', 'n1')));
  });

  it('the recipient can change only the read date', async () => {
    await assertSucceeds(updateDoc(doc(db('manager'), 'notifications', 'n1'), { readAt: '2026-11-18' }));
    await assertFails(updateDoc(doc(db('manager'), 'notifications', 'n1'), { title: 'Altceva' }));
  });
});

describe('new accounts', () => {
  const profile = (uid: string, farmId: string, role: string) => ({ uid, farmId, role, name: uid });

  it('creates its own farm and becomes administrator', async () => {
    const firestore = db('nou');
    await assertSucceeds(setDoc(doc(firestore, 'farms', 'farmN'), { ownerId: 'nou', name: 'New' }));
    await assertSucceeds(setDoc(doc(firestore, 'users', 'nou'), profile('nou', 'farmN', 'ADMIN')));
  });

  it('cannot become administrator in a foreign farm', async () => {
    await assertFails(setDoc(doc(db('hacker'), 'users', 'hacker'), profile('hacker', 'farmA', 'ADMIN')));
  });

  it('joins a farm only with an invitation and a verified email', async () => {
    const verified = { email: 'nou@farm.test', email_verified: true };
    await assertFails(setDoc(doc(db('inv1', { ...verified, email_verified: false }), 'users', 'inv1'), profile('inv1', 'farmA', 'OPERATOR')));
    await assertFails(setDoc(doc(db('inv2', verified), 'users', 'inv2'), profile('inv2', 'farmA', 'FARM_MANAGER')));
    await assertSucceeds(setDoc(doc(db('inv3', verified), 'users', 'inv3'), profile('inv3', 'farmA', 'OPERATOR')));
  });

  it('cannot read other people’s invitations', async () => {
    await assertFails(getDoc(doc(db('alt', { email: 'alt@farm.test', email_verified: true }), 'invites', 'nou@farm.test')));
  });

  it('a member cannot create another farm', async () => {
    await assertFails(setDoc(doc(db('operator'), 'farms', 'farmX'), { ownerId: 'operator' }));
  });

  it('a member cannot delete parcels', async () => {
    await assertFails(deleteDoc(doc(db('manager'), 'parcels', 'p1')));
  });
});
