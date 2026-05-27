import { Router } from 'express'
import { db } from '../db/client'
import { facilities } from 'pe-sub-db/src/schema'
import { eq } from 'drizzle-orm'

export const facilitiesRouter = Router()

facilitiesRouter.get('/', async (_req, res, next) => {
  try {
    const rows = await db.select().from(facilities).orderBy(facilities.name)
    res.json(rows)
  } catch (err) { next(err) }
})

facilitiesRouter.get('/:id', async (req, res, next) => {
  try {
    const [row] = await db.select().from(facilities).where(eq(facilities.id, Number(req.params.id)))
    if (!row) { res.status(404).json({ error: 'Not found' }); return }
    res.json(row)
  } catch (err) { next(err) }
})

facilitiesRouter.patch('/:id/status', async (req, res, next) => {
  try {
    const { status } = req.body as { status: string }
    const [updated] = await db
      .update(facilities)
      .set({ status, updatedAt: new Date() })
      .where(eq(facilities.id, Number(req.params.id)))
      .returning()
    res.json(updated)
  } catch (err) { next(err) }
})
