import { readFileSync } from 'node:fs';
import { assertFails, assertSucceeds, initializeTestEnvironment, type RulesTestEnvironment } from '@firebase/rules-unit-testing';
import { collection, deleteDoc, doc, getDoc, getDocs, query, setDoc, updateDoc, where, writeBatch } from 'firebase/firestore';
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
    await setDoc(doc(admin, 'farms', 'farmA'), { ownerId: 'admin', name: 'Ferma A' });
    await setDoc(doc(admin, 'parcels', 'p1'), { farmId: 'farmA', ownerId: 'admin', name: 'Sud' });
    await setDoc(doc(admin, 'tasks', 't1'), { farmId: 'farmA', title: 'Task', status: 'TODO', responsibleUser: 'operator' });
    await setDoc(doc(admin, 'tasks', 't2'), { farmId: 'farmA', title: 'Altul', status: 'TODO', responsibleUser: 'manager' });
    await setDoc(doc(admin, 'reports', 'r1'), { farmId: 'farmA', expectedProfitEurHa: 100 });
    await setDoc(doc(admin, 'expenses', 'e1'), { farmId: 'farmA', amount: 100 });
    await setDoc(doc(admin, 'machineWork', 'w1'), { farmId: 'farmA', operatorId: 'manager', status: 'RUNNING' });
    await setDoc(doc(admin, 'notifications', 'n1'), { farmId: 'farmA', userId: 'manager', title: 'Salut' });
    await setDoc(doc(admin, 'invites', 'nou@ferma.ro'), { farmId: 'farmA', role: 'OPERATOR' });
  });
});

describe('izolarea fermelor', () => {
  it('permite citirea datelor propriei ferme', async () => {
    await assertSucceeds(getDoc(doc(db('operator'), 'parcels', 'p1')));
  });

  it('interzice citirea și modificarea datelor altei ferme', async () => {
    await assertFails(getDoc(doc(db('stranger'), 'parcels', 'p1')));
    await assertFails(updateDoc(doc(db('stranger'), 'parcels', 'p1'), { name: 'Furat' }));
    await assertFails(getDoc(doc(db('stranger'), 'users', 'admin')));
  });

  it('interzice crearea de documente în altă fermă', async () => {
    await assertFails(setDoc(doc(db('stranger'), 'parcels', 'p2'), { farmId: 'farmA', ownerId: 'stranger' }));
  });

  it('interzice mutarea unui document în altă fermă', async () => {
    await assertFails(updateDoc(doc(db('admin'), 'parcels', 'p1'), { farmId: 'farmB' }));
  });

  it('interzice accesul fără autentificare', async () => {
    await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), 'parcels', 'p1')));
  });

  it('interzice colecțiile necunoscute', async () => {
    await assertFails(setDoc(doc(db('admin'), 'secrete', 'x'), { farmId: 'farmA' }));
  });
});

describe('roluri', () => {
  it('nu permite utilizatorului să își schimbe rolul sau ferma', async () => {
    await assertFails(updateDoc(doc(db('operator'), 'users', 'operator'), { role: 'ADMIN' }));
    await assertFails(updateDoc(doc(db('operator'), 'users', 'operator'), { farmId: 'farmB' }));
    await assertSucceeds(updateDoc(doc(db('operator'), 'users', 'operator'), { name: 'Ion' }));
  });

  it('permite administratorului să schimbe rolul unui membru', async () => {
    await assertSucceeds(updateDoc(doc(db('admin'), 'users', 'operator'), { role: 'AGRONOMIST' }));
    await assertFails(updateDoc(doc(db('manager'), 'users', 'operator'), { role: 'ADMIN' }));
  });

  it('nu permite operatorului și agronomului să creeze parcele', async () => {
    await assertFails(setDoc(doc(db('operator'), 'parcels', 'p3'), { farmId: 'farmA', ownerId: 'operator' }));
    await assertFails(setDoc(doc(db('agronomist'), 'parcels', 'p3'), { farmId: 'farmA', ownerId: 'agronomist' }));
    await assertSucceeds(setDoc(doc(db('manager'), 'parcels', 'p3'), { farmId: 'farmA', ownerId: 'manager' }));
  });

  it('permite agronomului doar câmpurile de planificare ale parcelei', async () => {
    await assertSucceeds(updateDoc(doc(db('agronomist'), 'parcels', 'p1'), { plannedCrop: 'SOY', rotationScore: 90 }));
    await assertFails(updateDoc(doc(db('agronomist'), 'parcels', 'p1'), { name: 'Schimbat' }));
  });

  it('permite operatorului doar statusul task-urilor proprii', async () => {
    await assertSucceeds(updateDoc(doc(db('operator'), 'tasks', 't1'), { status: 'DONE' }));
    await assertFails(updateDoc(doc(db('operator'), 'tasks', 't1'), { title: 'Schimbat' }));
    await assertFails(updateDoc(doc(db('operator'), 'tasks', 't2'), { status: 'DONE' }));
  });

  it('ascunde datele financiare de operatori', async () => {
    await assertFails(getDoc(doc(db('operator'), 'reports', 'r1')));
    await assertFails(getDoc(doc(db('operator'), 'expenses', 'e1')));
    await assertSucceeds(getDoc(doc(db('agronomist'), 'reports', 'r1')));
  });

  it('permite operatorului să controleze doar lucrările proprii', async () => {
    await assertFails(updateDoc(doc(db('operator'), 'machineWork', 'w1'), { status: 'PAUSED' }));
    await assertFails(setDoc(doc(db('operator'), 'machineWork', 'w2'), { farmId: 'farmA', operatorId: 'manager' }));
    await assertSucceeds(setDoc(doc(db('operator'), 'machineWork', 'w3'), { farmId: 'farmA', operatorId: 'operator' }));
  });
});

describe('listele folosite de aplicație', () => {
  const farmList = (uid: string, name: string, farmId = 'farmA') =>
    getDocs(query(collection(db(uid), name), where('farmId', '==', farmId)));

  it('permite listarea colecțiilor propriei ferme', async () => {
    for (const name of ['users', 'parcels', 'cropHistory', 'tasks', 'machines', 'machineWork', 'irrigations', 'cropPlans', 'observations']) {
      await assertSucceeds(farmList('operator', name));
    }
  });

  it('interzice listarea fără filtrul de fermă sau pentru altă fermă', async () => {
    await assertFails(getDocs(collection(db('admin'), 'parcels')));
    await assertFails(farmList('stranger', 'parcels'));
  });

  it('listează rapoartele doar pentru planificatori', async () => {
    await assertSucceeds(farmList('manager', 'reports'));
    await assertFails(farmList('operator', 'reports'));
  });

  it('listează doar notificările proprii', async () => {
    const own = query(collection(db('manager'), 'notifications'), where('farmId', '==', 'farmA'), where('userId', '==', 'manager'));
    await assertSucceeds(getDocs(own));
    await assertFails(farmList('manager', 'notifications'));
  });

  it('salvează planul complet al unei parcele într-o singură operațiune', async () => {
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

describe('catalog', () => {
  it('este vizibil pentru toată ferma și modificabil doar de administrator', async () => {
    await assertSucceeds(setDoc(doc(db('admin'), 'seedCatalog', 's1'), { farmId: 'farmA', name: 'P0710' }));
    await assertSucceeds(getDoc(doc(db('operator'), 'seedCatalog', 's1')));
    await assertFails(updateDoc(doc(db('manager'), 'seedCatalog', 's1'), { name: 'Altul' }));
    await assertFails(setDoc(doc(db('agronomist'), 'machineCatalog', 'm1'), { farmId: 'farmA', model: '8R' }));
    await assertFails(deleteDoc(doc(db('operator'), 'seedCatalog', 's1')));
  });

  it('nu poate fi citit sau scris de altă fermă', async () => {
    await assertSucceeds(setDoc(doc(db('admin'), 'machineCatalog', 'm2'), { farmId: 'farmA', model: '8R' }));
    await assertFails(getDoc(doc(db('stranger'), 'machineCatalog', 'm2')));
    await assertFails(setDoc(doc(db('stranger'), 'implementCatalog', 'i1'), { farmId: 'farmA' }));
  });
});

describe('notificări', () => {
  it('sunt vizibile doar destinatarului', async () => {
    await assertSucceeds(getDoc(doc(db('manager'), 'notifications', 'n1')));
    await assertFails(getDoc(doc(db('admin'), 'notifications', 'n1')));
  });

  it('destinatarul poate modifica doar data citirii', async () => {
    await assertSucceeds(updateDoc(doc(db('manager'), 'notifications', 'n1'), { readAt: '2026-11-18' }));
    await assertFails(updateDoc(doc(db('manager'), 'notifications', 'n1'), { title: 'Altceva' }));
  });
});

describe('conturi noi', () => {
  const profile = (uid: string, farmId: string, role: string) => ({ uid, farmId, role, name: uid });

  it('creează o fermă proprie și devine administrator', async () => {
    const firestore = db('nou');
    await assertSucceeds(setDoc(doc(firestore, 'farms', 'farmN'), { ownerId: 'nou', name: 'Nouă' }));
    await assertSucceeds(setDoc(doc(firestore, 'users', 'nou'), profile('nou', 'farmN', 'ADMIN')));
  });

  it('nu poate deveni administrator într-o fermă străină', async () => {
    await assertFails(setDoc(doc(db('hacker'), 'users', 'hacker'), profile('hacker', 'farmA', 'ADMIN')));
  });

  it('intră în fermă doar cu invitație și email verificat', async () => {
    const verified = { email: 'nou@ferma.ro', email_verified: true };
    await assertFails(setDoc(doc(db('inv1', { ...verified, email_verified: false }), 'users', 'inv1'), profile('inv1', 'farmA', 'OPERATOR')));
    await assertFails(setDoc(doc(db('inv2', verified), 'users', 'inv2'), profile('inv2', 'farmA', 'FARM_MANAGER')));
    await assertSucceeds(setDoc(doc(db('inv3', verified), 'users', 'inv3'), profile('inv3', 'farmA', 'OPERATOR')));
  });

  it('nu poate citi invitațiile altora', async () => {
    await assertFails(getDoc(doc(db('alt', { email: 'alt@ferma.ro', email_verified: true }), 'invites', 'nou@ferma.ro')));
  });

  it('un membru nu își poate crea altă fermă', async () => {
    await assertFails(setDoc(doc(db('operator'), 'farms', 'farmX'), { ownerId: 'operator' }));
  });

  it('un membru nu poate șterge parcele', async () => {
    await assertFails(deleteDoc(doc(db('manager'), 'parcels', 'p1')));
  });
});
